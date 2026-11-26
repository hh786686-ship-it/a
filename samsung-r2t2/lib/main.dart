import 'dart:async';
import 'dart:io';
import 'dart:isolate';
import 'dart:math' as math;
import 'dart:typed_data';
import 'package:crypto/crypto.dart';

import 'package:crispasr/crispasr.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'package:record/record.dart';

const modelUrl =
    'https://huggingface.co/cstr/confucius4-r2t2-GGUF/resolve/main/confucius4-r2t2-q4_k.gguf?download=true';
const modelFile = 'confucius4-r2t2-q4_k.gguf';
const modelBytes = 1490915264;
const modelHash = 'a754788730d7b2a62542de295d37e431e79795d5bea418fa0e4f9618f0f8e3b3';

void main() => runApp(const MaterialApp(
  home: LiveCaptions(), debugShowCheckedModeBanner: false,
));

/// Recognition runs outside the UI isolate to keep the microphone and screen responsive.
void recognitionWorker(SendPort parent) async {
  final inbox = ReceivePort();
  parent.send(inbox.sendPort);
  CrispasrSession? session;
  int activeRun = -1;

  void closeStream() {
    activeRun = -1;
  }

  await for (final message in inbox) {
    if (message is Map && message['op'] == 'load') {
      closeStream();
      try {
        session?.close();
        final layers = message['layers'] as int? ?? 0;
        if (layers > 0) setGpuBackend('vulkan');
        session = CrispasrSession.openWithParams(
          message['path'] as String,
          backend: 'qwen3', useGpu: layers > 0, nThreads: 4, nGpuLayers: layers, flashAttn: false, verbosity: 1,
        );
        final kind = session!.streamingKind;
        if (kind == 0) throw StateError('Android library has no streaming support.');
        parent.send({'type': 'ready', 'kind': kind});
      } catch (e) {
        try { session?.close(); } catch (_) {}
        session = null;
        parent.send({'type': 'error', 'message': '$e'});
      }
    } else if (message is Map && message['op'] == 'start') {
      final run = message['run'] as int;
      try {
        if (session == null) throw StateError('Load the model first.');
        closeStream();
        activeRun = run;
        parent.send({'type': 'started', 'run': run});
      } catch (e) {
        closeStream();
        parent.send({'type': 'error', 'run': run, 'message': '$e'});
      }
    } else if (message is Map && message['op'] == 'audio') {
      final run = message['run'] as int;
      if (run != activeRun || session == null) continue;
      try {
        final data = message['pcm'] as Uint8List;
        final n = data.length ~/ 2;
        if (n == 0) continue;
        final pcm = Float32List(n);
        final bytes = ByteData.sublistView(data);
        for (var i = 0; i < n; ++i) {
          pcm[i] = bytes.getInt16(i * 2, Endian.little) / 32768.0;
        }
        final watch = Stopwatch()..start();
        double energy = 0;
        for (final sample in pcm) { energy += sample * sample; }
        if (energy / n >= 0.000004) {
          // Independent bounded utterances: only a single feed per window,
          // no growing prefix or repeated processing of previous windows.
          final segment = session!.openStream(stepMs: 4000, lengthMs: 4000, nThreads: 6, language: 'zh');
          try {
            final partial = segment.feed(pcm);
            final finalResult = segment.flush();
            final text = (finalResult?.text ?? partial?.text ?? '').trim();
            if (text.isNotEmpty) parent.send({'type': 'chunkCaption', 'run': run, 'text': text});
          } finally { segment.close(); }
        }
        parent.send({'type': 'processed', 'run': run, 'bytes': data.length, 'ms': watch.elapsedMilliseconds});
      } catch (e) {
        parent.send({'type': 'error', 'run': run, 'message': '$e'});
      }
    } else if (message is Map && message['op'] == 'stop') {
      final run = message['run'] as int;
      if (run == activeRun) closeStream();
      parent.send({'type': 'stopped', 'run': run});
    } else if (message == 'shutdown') {
      closeStream();
      try { session?.close(); } catch (_) {}
      break;
    }
  }
  inbox.close();
}

/// Keeps the already-published characters fixed. Only the active row grows.
class CaptionLedger {
  static const int rowWidth = 15;
  final List<String> _history = [];
  String _active = '';
  String _lastModelText = '';

  void reset() {
    _history.clear();
    _active = '';
    _lastModelText = '';
  }

  void accept(String cumulative) {
    final now = cumulative.runes.toList();
    final old = _lastModelText.runes.toList();
    var matching = 0;
    while (matching < now.length &&
           matching < old.length && now[matching] == old[matching]) {
      matching++;
    }
    if (old.isEmpty || matching == old.length) {
      _append(now.skip(old.length));
      _lastModelText = cumulative;
    } else if (matching >= old.length - 2 && now.length > old.length) {
      _append(now.skip(old.length));
      _lastModelText = cumulative;
    }
  }

  void acceptChunk(String text) { _append(text.runes); }

  void _append(Iterable<int> runes) {
    for (final rune in runes) {
      final char = String.fromCharCode(rune);
      if (char == '\n' || char == '\r') continue;
      _active += char;
      if (_active.runes.length >= rowWidth) {
        _history.add(_active);
        if (_history.length > 4) _history.removeAt(0);
        _active = '';
      }
    }
  }

  List<String> get rows => List.unmodifiable([
    ...List<String>.filled(4 - _history.length, ''),
    ..._history,
    _active,
  ]);
}

class LiveCaptions extends StatefulWidget {
  const LiveCaptions({super.key});
  @override
  State<LiveCaptions> createState() => _LiveCaptionsState();
}

class _LiveCaptionsState extends State<LiveCaptions> {
  static const _overlay = MethodChannel('livechinese/overlay');
  bool _overlayVisible = false;

  final _recorder = AudioRecorder();
  StreamSubscription<Uint8List>? _micSub;
  ReceivePort? _updates;
  Isolate? _worker;
  SendPort? _send;
  final _captions = CaptionLedger();
  String _status = 'Download the model once. Interrupted downloads resume automatically.';
  bool _loaded = false, _listening = false, _downloading = false, _busy = false;
  int _run = 0, _font = 23;
  double _progress = 0;
  HttpClient? _downloadClient;
  bool _pauseDownload = false;
  bool _modelVerified = false;
  bool _loadingModel = false;
  int _gpuLayers = 4;
  File? _loadMarker;
  bool _checkingRecovery = true;

  Future<void> _clearLoadMarker() async {
    try { final marker = _loadMarker; if (marker != null && await marker.exists()) await marker.delete(); } catch (_) {}
  }
  bool _transferring = false;
  int _audioBytes = 0, _processedBytes = 0, _decodeMs = 0, _skippedBytes = 0;
  double _micLevel = 0;
  final BytesBuilder _pendingAudio = BytesBuilder(copy: true);
  bool _inFlight = false;
  DateTime _lastMeter = DateTime.fromMillisecondsSinceEpoch(0);

  @override
  void initState() {
    super.initState();
    _overlay.setMethodCallHandler((call) async {
      if (call.method == 'overlayDismissed') {
        _overlayVisible = false;
        if (_listening) await _toggleMic();
      }
    });
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final file = await _modelPath();
      _loadMarker = File('${file.parent.path}/gpu-load-in-progress');
      final interrupted = await _loadMarker!.exists();
      if (!mounted) return;
      setState(() {
        _checkingRecovery = false;
        if (interrupted) { _gpuLayers = 0; _status = 'Previous GPU load interrupted. CPU selected for safe recovery.'; }
      });
      await _clearLoadMarker();
    });
  }

  void _pumpAudio() {
    if (!_listening || _inFlight || _pendingAudio.length < 32000 * 3) return;
    final queued = _pendingAudio.takeBytes();
    final count = math.min(queued.length, 32000 * 3);
    _inFlight = true;
    _send?.send({'op': 'audio', 'run': _run, 'pcm': Uint8List.sublistView(queued, 0, count)});
    if (queued.length > count) _pendingAudio.add(Uint8List.sublistView(queued, count));
  }

  @override
  void dispose() {
    _overlay.setMethodCallHandler(null);
    _downloadClient?.close(force: true);
    _micSub?.cancel();
    _recorder.stop();
    _recorder.dispose();
    _send?.send('shutdown');
    _worker?.kill(priority: Isolate.immediate);
    _updates?.close();
    super.dispose();
  }

  Future<void> _showOverlay() async {
    try {
      // Foreground microphone services must be started while the activity
      // is visible and after microphone permission has been granted.
      if (!await _recorder.hasPermission()) {
        if (mounted) setState(() => _status = 'Please grant microphone permission first.');
        return;
      }
      final allowed = await _overlay.invokeMethod<bool>('hasOverlayPermission') ?? false;
      if (!allowed) {
        await _overlay.invokeMethod('requestOverlayPermission');
        if (mounted) setState(() => _status =
          'Enable "Appear on top", return here, and tap Show overlay again.');
        return;
      }
      await _overlay.invokeMethod('startOverlay');
      _overlayVisible = true;
      await _syncOverlay();
      if (mounted) setState(() => _status =
        'Floating overlay is visible. Start listening, then switch apps.');
    } catch (e) {
      if (mounted) setState(() => _status = 'Overlay error: $e');
    }
  }

  Future<void> _hideOverlay() async {
    try {
      await _overlay.invokeMethod('stopOverlay');
      _overlayVisible = false;
      if (mounted) setState(() => _status = 'Overlay closed.');
    } catch (e) {
      if (mounted) setState(() => _status = 'Overlay close error: $e');
    }
  }

  Future<void> _syncOverlay() async {
    if (!_overlayVisible) return;
    try {
      await _overlay.invokeMethod('setCaptions', {'lines': _captions.rows});
    } catch (_) {
      // The overlay can be closed from its own controls at any time.
      _overlayVisible = false;
    }
  }

  Future<File> _modelPath() async {
    final dir = await getApplicationSupportDirectory();
    final models = Directory('${dir.path}/models');
    await models.create(recursive: true);
    return File('${models.path}/$modelFile');
  }

  Future<bool> _verifyModel(File file) async {
    if (!await file.exists() || await file.length() != modelBytes) return false;
    final digest = await sha256.bind(file.openRead()).first;
    return digest.toString() == modelHash;
  }

  Future<void> _transferModel(bool exporting) async {
    if (_transferring || _downloading || _loadingModel || (!exporting && _loaded)) return;
    setState(() { _transferring = true; _status = exporting ? 'Choose a destination for model backup…' : 'Choose your GGUF model file…'; });
    File? temporary;
    try {
      final file = await _modelPath();
      if (exporting) {
        if (!_modelVerified && !await _verifyModel(file)) throw StateError('No verified model to export.');
        final ok = await _overlay.invokeMethod<bool>('exportModel', {'path': file.path}) ?? false;
        if (mounted) setState(() => _status = ok ? 'Model backup saved.' : 'Backup cancelled.');
      } else {
        if (await _verifyModel(file)) {
          _modelVerified = true;
          if (mounted) setState(() => _status = 'Verified model already present.');
          return;
        }
        temporary = File('${file.path}.import');
        final ok = await _overlay.invokeMethod<bool>('importModel', {'path': temporary.path}) ?? false;
        if (!ok) { if (mounted) setState(() => _status = 'Import cancelled.'); return; }
        if (mounted) setState(() => _status = 'Verifying imported model…');
        if (!await _verifyModel(temporary)) throw StateError('Incorrect model SHA-256.');
        await temporary.rename(file.path);
        _modelVerified = true;
        if (mounted) setState(() => _status = 'Imported verified model. Tap Load model.');
      }
    } catch (e) {
      if (mounted) setState(() => _status = 'Model transfer error: $e');
    } finally {
      if (temporary != null && await temporary.exists()) await temporary.delete();
      if (mounted) setState(() => _transferring = false);
    }
  }

  Future<void> _download() async {
    if (_downloading || _loaded || _loadingModel || _transferring) return;
    _pauseDownload = false;
    setState(() { _downloading = true; _status = 'Checking model and free space…'; });
    try {
      final file = await _modelPath();
      if (_modelVerified || await _verifyModel(file)) {
        _modelVerified = true;
        if (mounted) setState(() { _progress = 1; _status = 'Verified model ready. Tap Load model.'; });
        return;
      }
      // Keep just one file. A partial download is renamed in place after verification.
      if (await file.exists()) await file.delete();
      final partial = File('${file.path}.part');
      if (await partial.exists() && await partial.length() > modelBytes) await partial.delete();
      for (var attempt = 0; attempt < 5 && !_pauseDownload; attempt++) {
        final offset = await partial.exists() ? await partial.length() : 0;
        if (offset == modelBytes) break;
        final free = await _overlay.invokeMethod<int>('availableStorage', {'path': file.parent.path});
        if (free != null && free < modelBytes - offset + 64 * 1024 * 1024) {
          throw StateError('Not enough internal storage for remaining model bytes.');
        }
        final client = HttpClient()..connectionTimeout = const Duration(seconds: 25)..autoUncompress = false;
        _downloadClient = client;
        RandomAccessFile? output;
        try {
          final request = await client.getUrl(Uri.parse(modelUrl)).timeout(const Duration(seconds: 30));
          request.headers.set(HttpHeaders.acceptEncodingHeader, 'identity');
          request.headers.set(HttpHeaders.rangeHeader, 'bytes=$offset-');
          final response = await request.close().timeout(const Duration(seconds: 45));
          final range = response.headers.value(HttpHeaders.contentRangeHeader);
          if (response.statusCode == 206) {
            final match = RegExp(r'^bytes (\d+)-(\d+)/(\d+)$').firstMatch(range ?? '');
            if (match == null || int.parse(match.group(1)!) != offset ||
                int.parse(match.group(3)!) != modelBytes) {
              throw StateError('Server returned an incorrect byte range.');
            }
          } else if (response.statusCode != 200 || offset != 0) {
            throw StateError('Cannot safely resume: HTTP ${response.statusCode}. Partial download kept.');
          }
          output = await partial.open(mode: FileMode.append);
          var received = offset;
          var lastUpdate = DateTime.now().subtract(const Duration(seconds: 1));
          await for (final bytes in response.timeout(const Duration(seconds: 35))) {
            if (_pauseDownload) break;
            if (received + bytes.length > modelBytes) throw StateError('Unexpected model length.');
            await output.writeFrom(bytes);
            received += bytes.length;
            final now = DateTime.now();
            if (mounted && now.difference(lastUpdate).inMilliseconds >= 500) {
              lastUpdate = now;
              setState(() { _progress = received / modelBytes;
                _status = 'Downloading: ${(received / 1000000).toStringAsFixed(0)} / 1491 MB'; });
            }
          }
          await output.flush();
        } catch (e) {
          if (_pauseDownload) break;
          if (attempt == 4) rethrow;
        } finally {
          await output?.close();
          client.close(force: true);
          _downloadClient = null;
        }
        if (!_pauseDownload && await partial.exists() && await partial.length() < modelBytes) {
          if (mounted) setState(() => _status = 'Connection interrupted. Resuming automatically…');
          await Future<void>.delayed(Duration(seconds: 2 * (attempt + 1)));
        }
      }
      if (_pauseDownload) {
        if (mounted) setState(() => _status = 'Paused. Tap Download / resume to continue.');
        return;
      }
      if (!await partial.exists() || await partial.length() != modelBytes) {
        throw StateError('Download incomplete. Tap Download / resume.');
      }
      if (mounted) setState(() { _progress = 1; _status = 'Verifying SHA-256 checksum…'; });
      if (!await _verifyModel(partial)) {
        await partial.delete();
        throw StateError('Model checksum mismatch. Download again.');
      }
      await partial.rename(file.path);
      _modelVerified = true;
      if (mounted) setState(() => _status = 'Verified model ready. Tap Load model.');
    } catch (e) {
      if (mounted) setState(() => _status = _pauseDownload ? 'Paused. Tap Download / resume.' : 'Download error: $e');
    } finally {
      if (mounted) setState(() => _downloading = false);
    }
  }

  void _pause() {
    _pauseDownload = true;
    _downloadClient?.close(force: true);
  }

  Future<void> _load() async {
    if (_loaded || _updates != null || _downloading || _checkingRecovery) return;
    if (_loadingModel || _transferring) return;
    _loadingModel = true;
    final model = await _modelPath();
    if (mounted) setState(() => _status = 'Checking installed model…');
    try {
      _modelVerified = _modelVerified || await _verifyModel(model);
    } catch (_) { _modelVerified = false; }
    if (!mounted) return;
    if (!_modelVerified) {
      setState(() { _loadingModel = false; _status = 'Tap Download / resume to obtain the verified model first.'; });
      return;
    }
    try { await _overlay.invokeMethod('keepModelWarm'); } catch (_) {}
    if (!mounted) return;
    final layers = _gpuLayers;
    setState(() => _status = 'Loading model ($layers GPU layers)…');
    try {
      if (layers > 0) await _loadMarker!.writeAsString('$layers', flush: true);
      _updates = ReceivePort();
      _worker = await Isolate.spawn(recognitionWorker, _updates!.sendPort, onError: _updates!.sendPort, onExit: _updates!.sendPort);
      _updates!.listen((message) {
        if (message is SendPort) {
          _send = message;
          _send!.send({'op': 'load', 'path': model.path, 'layers': layers});
        } else if ((message == null || message is List) && mounted) {
          _micSub?.cancel();
          unawaited(_recorder.stop());
          _updates?.close(); _updates = null; _send = null; _worker = null;
          setState(() { _loadingModel = false; _gpuLayers = 0; _loaded = false; _listening = false; _status = 'Recognition worker stopped: ${message ?? "possibly low memory"}'; });
        } else if (message is Map && mounted) {
          final run = message['run'];
          if (run is int && run != _run) return;
          switch (message['type']) {
            case 'ready':
              unawaited(_clearLoadMarker());
              setState(() { _loadingModel = false; _loaded = true; _status = layers > 0 ? '$layers GPU layers requested. Tap Start listening.' : 'CPU mode ready. Tap Start listening.'; });
              break;
            case 'started':
              setState(() => _status = 'Listening to phone speaker…');
              break;
            case 'error':
              if (!_loaded) { _updates?.close(); _updates = null; _worker?.kill(); _worker = null; _send = null; _loadingModel = false; _gpuLayers = 0; unawaited(_clearLoadMarker()); }
              _inFlight = false;
              setState(() => _status = 'Recognition error: ${message['message']}');
              break;
            case 'processed':
              _inFlight = false;
              setState(() { _processedBytes += message['bytes'] as int; _decodeMs = message['ms'] as int; });
              _pumpAudio();
              break;
            case 'chunkCaption':
              if (_listening) { setState(() => _captions.acceptChunk(message['text'] as String)); unawaited(_syncOverlay()); }
              break;
            case 'caption':
              if (_listening) {
                setState(() => _captions.accept(message['text'] as String));
                unawaited(_syncOverlay());
              }
              break;
          }
        }
      });
    } catch (e) {
      _updates?.close();
      _updates = null;
      if (mounted) setState(() { _loadingModel = false; _gpuLayers = 0; _status = 'Cannot start model: $e'; });
    }
  }

  Future<void> _toggleMic() async {
    if (_busy) return;
    _busy = true;
    try {
      if (_listening) {
        await _micSub?.cancel();
        _micSub = null;
        await _recorder.stop();
        try { await _overlay.invokeMethod('stopMicService'); } catch (_) {}
        _send?.send({'op': 'stop', 'run': _run});
        if (mounted) setState(() { _listening = false; _status = 'Stopped'; });
        // Keep the overlay visible to preserve the last subtitles.
        return;
      }
      if (!_loaded || _send == null) {
        if (mounted) setState(() => _status = 'Load the model first.');
        return;
      }
      if (!await _recorder.hasPermission()) {
        if (mounted) setState(() => _status = 'Microphone permission is needed.');
        return;
      }
      await _overlay.invokeMethod('startMicService');
      final nextRun = ++_run;
      _captions.reset();
      _audioBytes = 0; _processedBytes = 0; _decodeMs = 0; _skippedBytes = 0; _micLevel = 0;
      _pendingAudio.clear(); _inFlight = false;
      unawaited(_syncOverlay());
      _send!.send({'op': 'start', 'run': nextRun});
      final audio = await _recorder.startStream(const RecordConfig(
        encoder: AudioEncoder.pcm16bits,
        sampleRate: 16000, numChannels: 1,
        autoGain: false, echoCancel: false, noiseSuppress: false,
      ));
      _micSub = audio.listen((bytes) {
        _audioBytes += bytes.length;
        final now = DateTime.now();
        if (now.difference(_lastMeter).inMilliseconds >= 250 && bytes.length >= 2 && mounted) {
          _lastMeter = now;
          final pcm = ByteData.sublistView(bytes);
          double sum = 0;
          for (var i = 0; i + 1 < bytes.length; i += 2) { final v = pcm.getInt16(i, Endian.little) / 32768.0; sum += v * v; }
          setState(() => _micLevel = math.sqrt(sum / (bytes.length ~/ 2)));
        }
        _pendingAudio.add(bytes);
        if (_pendingAudio.length > 32000 * 6) {
          final queued = _pendingAudio.takeBytes();
          final excess = queued.length - 32000 * 6;
          _skippedBytes += excess;
          _pendingAudio.add(Uint8List.sublistView(queued, excess));
          if (mounted) setState(() => _status = 'CPU cannot keep up; older audio skipped.');
        }
        _pumpAudio();
      }, onError: (Object e) { if (mounted) setState(() => _status = 'Microphone stream error: $e'); });
      if (mounted) setState(() {
        _listening = true;
        _status = 'Listening to phone speaker…';
      });
    } catch (e) {
      _send?.send({'op': 'stop', 'run': _run});
      try { await _overlay.invokeMethod('stopMicService'); } catch (_) {}
      if (mounted) setState(() => _status = 'Microphone error: $e');
    } finally {
      _busy = false;
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: const Color(0xff111827),
    appBar: AppBar(title: const Text('Live Chinese — R2T2'),
      backgroundColor: const Color(0xff243044), foregroundColor: Colors.white),
    body: SafeArea(child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(children: [
        const Text('Place your other phone on speaker beside this Samsung.',
          style: TextStyle(color: Colors.white70)),
        const SizedBox(height: 15),
        Expanded(child: Container(
          width: double.infinity,
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(color: const Color(0xff374151),
            borderRadius: BorderRadius.circular(15)),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: List.generate(5, (i) {
              final rows = _captions.rows;
              return Expanded(child: Align(
                alignment: Alignment.centerLeft,
                child: Text(rows[i], maxLines: 1, overflow: TextOverflow.clip,
                  style: TextStyle(fontSize: _font.toDouble(),
                    color: Colors.white, fontWeight: FontWeight.w500)),
              ));
            }),
          ),
        )),
        const SizedBox(height: 15),
        if (!_loaded && !_loadingModel && _updates == null && !_checkingRecovery)
          DropdownButton<int>(dropdownColor: const Color(0xff243044), value: _gpuLayers, style: const TextStyle(color: Colors.white), items: const [DropdownMenuItem(value: 0, child: Text('CPU only')), DropdownMenuItem(value: 4, child: Text('GPU 4 layers (recommended test)')), DropdownMenuItem(value: 8, child: Text('GPU 8 layers')), DropdownMenuItem(value: 16, child: Text('GPU 16 layers'))], onChanged: (v) { if (v != null) setState(() => _gpuLayers = v); }),
        Text(_status, style: const TextStyle(color: Color(0xffcbd5e1))),
        if (_listening) ...[
          LinearProgressIndicator(value: (_micLevel * 8).clamp(0.0, 1.0).toDouble()),
          Text('Mic ${(20 * math.log(math.max(_micLevel, 0.00001)) / math.ln10).toStringAsFixed(0)} dBFS · received ${(_audioBytes / 32000).toStringAsFixed(1)}s · processed ${(_processedBytes / 32000).toStringAsFixed(1)}s · last decode ${_decodeMs}ms · skipped ${(_skippedBytes / 32000).toStringAsFixed(1)}s', style: const TextStyle(color: Colors.white70, fontSize: 12)),
        ],
        if (_downloading) LinearProgressIndicator(
          value: _progress == 0 ? null : _progress),
        const SizedBox(height: 8),
        Wrap(alignment: WrapAlignment.center, spacing: 10, children: [
          ElevatedButton(onPressed: (_loaded || _loadingModel) ? null : (_downloading ? _pause : _download),
            child: Text(_downloading ? 'Pause download' : '1. Download / resume')),
          ElevatedButton(onPressed: (_loaded || _downloading || _loadingModel || _checkingRecovery) ? null : _load,
            child: const Text('2. Load model')),
          ElevatedButton(onPressed: (_transferring || _downloading || _loaded || _loadingModel) ? null : () => _transferModel(false),
            child: const Text('Import model')),
          ElevatedButton(onPressed: (_transferring || _downloading || !_modelVerified) ? null : () => _transferModel(true),
            child: const Text('Backup model')),
          ElevatedButton(onPressed: _toggleMic,
            child: Text(_listening ? 'Stop listening' : '3. Start listening')),
          ElevatedButton(onPressed: _showOverlay,
            child: const Text('4. Show floating overlay')),
          ElevatedButton(onPressed: _hideOverlay,
            child: const Text('Close overlay')),
        ]),
        Row(children: [
          const Icon(Icons.text_fields, color: Colors.white),
          Expanded(child: Slider(min: 17, max: 34, divisions: 17,
            value: _font.toDouble(),
            onChanged: (value) => setState(() => _font = value.round()))),
        ]),
      ]),
    )),
  );
}
