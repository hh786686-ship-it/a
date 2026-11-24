import 'dart:async';
import 'dart:io';
import 'dart:isolate';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:crispasr/crispasr.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:record/record.dart';

const modelUrl =
    'https://huggingface.co/cstr/confucius4-r2t2-GGUF/resolve/main/confucius4-r2t2-q4_k.gguf?download=true';
const modelFile = 'confucius4-r2t2-q4_k.gguf';

void main() => runApp(const MaterialApp(
  home: LiveCaptions(), debugShowCheckedModeBanner: false,
));

/// Recognition runs outside the UI isolate to keep the microphone and screen responsive.
void recognitionWorker(SendPort parent) async {
  final inbox = ReceivePort();
  parent.send(inbox.sendPort);
  CrispasrSession? session;
  StreamingSession? stream;
  int activeRun = -1;

  void closeStream() {
    try { stream?.flush(); } catch (_) {}
    try { stream?.close(); } catch (_) {}
    stream = null;
    activeRun = -1;
  }

  await for (final message in inbox) {
    if (message is Map && message['op'] == 'load') {
      closeStream();
      try {
        session?.close();
        session = CrispasrSession.openWithParams(
          message['path'] as String,
          backend: 'qwen3', useGpu: false, nThreads: 4, nGpuLayers: 0,
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
        stream = session!.openStream(
          stepMs: 160, lengthMs: 2000, nThreads: 4, language: 'zh',
        );
        activeRun = run;
        parent.send({'type': 'started', 'run': run});
      } catch (e) {
        closeStream();
        parent.send({'type': 'error', 'run': run, 'message': '$e'});
      }
    } else if (message is Map && message['op'] == 'audio') {
      final run = message['run'] as int;
      if (run != activeRun || stream == null) continue;
      try {
        final data = message['pcm'] as Uint8List;
        final n = data.length ~/ 2;
        if (n == 0) continue;
        final pcm = Float32List(n);
        final bytes = ByteData.sublistView(data);
        for (var i = 0; i < n; ++i) {
          pcm[i] = bytes.getInt16(i * 2, Endian.little) / 32768.0;
        }
        final result = stream!.feed(pcm);
        if (result != null && result.text.trim().isNotEmpty) {
          parent.send({'type': 'caption', 'run': run, 'text': result.text});
        }
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
  String _status = 'Download the model once to enable offline recognition.';
  bool _loaded = false, _listening = false, _downloading = false, _busy = false;
  int _run = 0, _font = 23;
  double _progress = 0;

  @override
  void dispose() {
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

  Future<void> _download() async {
    if (_downloading) return;
    setState(() { _downloading = true; _status = 'Downloading the model over Wi-Fi…'; });
    try {
      final file = await _modelPath();
      if (await file.exists() && await file.length() > 500 * 1024 * 1024) {
        if (mounted) setState(() => _status = 'Model already downloaded.');
        return;
      }
      final partial = File('${file.path}.part');
      var existing = await partial.exists() ? await partial.length() : 0;
      final client = http.Client();
      try {
        final request = http.Request('GET', Uri.parse(modelUrl));
        if (existing > 0) request.headers['Range'] = 'bytes=$existing-';
        final response = await client.send(request);
        if (response.statusCode != 200 && response.statusCode != 206) {
          throw StateError('Download HTTP ${response.statusCode}');
        }
        // Servers are allowed to ignore Range. Never append a complete response.
        if (existing > 0 && response.statusCode != 206) {
          await partial.delete();
          existing = 0;
        }
        final expected = response.contentLength == null
            ? null : existing + response.contentLength!;
        final sink = partial.openWrite(mode: existing > 0 ? FileMode.append : FileMode.write);
        var received = existing;
        try {
          await for (final bytes in response.stream) {
            sink.add(bytes);
            received += bytes.length;
            if (mounted) setState(() {
              _progress = expected == null ? 0 : math.min(1, received / expected);
              _status = 'Model: ${(received / 1048576).toStringAsFixed(0)} MB';
            });
          }
        } finally {
          await sink.close();
        }
        if (expected != null && received != expected) {
          throw StateError('Incomplete download. Try again to resume.');
        }
        if (received < 500 * 1024 * 1024) {
          throw StateError('Model file too small. Try again.');
        }
        if (await file.exists()) await file.delete();
        await partial.rename(file.path);
        if (mounted) setState(() => _status = 'Model downloaded. Ready for offline use.');
      } finally {
        client.close();
      }
    } catch (e) {
      if (mounted) setState(() => _status = 'Download error: $e');
    } finally {
      if (mounted) setState(() => _downloading = false);
    }
  }

  Future<void> _load() async {
    if (_loaded || _updates != null) return;
    final model = await _modelPath();
    if (!await model.exists() || await model.length() < 500 * 1024 * 1024) {
      setState(() => _status = 'Download the Q4 model first.');
      return;
    }
    setState(() => _status = 'Loading model into phone memory…');
    _updates = ReceivePort();
    try {
      _worker = await Isolate.spawn(recognitionWorker, _updates!.sendPort);
      _updates!.listen((message) {
        if (message is SendPort) {
          _send = message;
          _send!.send({'op': 'load', 'path': model.path});
        } else if (message is Map && mounted) {
          final run = message['run'];
          if (run is int && run != _run) return;
          switch (message['type']) {
            case 'ready':
              setState(() { _loaded = true; _status = 'Ready. Tap Start listening.'; });
              break;
            case 'started':
              setState(() => _status = 'Listening to phone speaker…');
              break;
            case 'error':
              setState(() => _status = 'Recognition error: ${message['message']}');
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
      if (mounted) setState(() => _status = 'Cannot start model: $e');
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
      final nextRun = ++_run;
      _captions.reset();
      unawaited(_syncOverlay());
      _send!.send({'op': 'start', 'run': nextRun});
      final audio = await _recorder.startStream(const RecordConfig(
        encoder: AudioEncoder.pcm16bits,
        sampleRate: 16000, numChannels: 1,
        autoGain: false, echoCancel: false, noiseSuppress: false,
      ));
      _micSub = audio.listen((bytes) {
        _send?.send({'op': 'audio', 'run': nextRun, 'pcm': bytes});
      });
      if (mounted) setState(() {
        _listening = true;
        _status = 'Listening to phone speaker…';
      });
    } catch (e) {
      _send?.send({'op': 'stop', 'run': _run});
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
        Text(_status, style: const TextStyle(color: Color(0xffcbd5e1))),
        if (_downloading) LinearProgressIndicator(
          value: _progress == 0 ? null : _progress),
        const SizedBox(height: 8),
        Wrap(alignment: WrapAlignment.center, spacing: 10, children: [
          ElevatedButton(onPressed: _downloading ? null : _download,
            child: const Text('1. Download model')),
          ElevatedButton(onPressed: _loaded ? null : _load,
            child: const Text('2. Load model')),
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
