import 'dart:ffi' as ffi;
import 'dart:typed_data';
import 'package:ffi/ffi.dart';
class LlamaSession {
  final ffi.DynamicLibrary lib;
  final ffi.Pointer<ffi.Void> handle;
  LlamaSession._(this.lib,this.handle);
  static LlamaSession open(String model,String projector,int threads,int layers,bool encoderGpu,int tokens) {
    final lib=ffi.DynamicLibrary.open('liblivechinese_llama.so');
    final fn=lib.lookupFunction<ffi.Pointer<ffi.Void> Function(ffi.Pointer<Utf8>,ffi.Pointer<Utf8>,ffi.Int32,ffi.Int32,ffi.Int32,ffi.Int32),ffi.Pointer<ffi.Void> Function(ffi.Pointer<Utf8>,ffi.Pointer<Utf8>,int,int,int,int)>('lc_open');
    final m=model.toNativeUtf8(), p=projector.toNativeUtf8();
    try { final h=fn(m,p,threads,layers,encoderGpu?1:0,tokens); if(h==ffi.nullptr) throw StateError(_error(lib)); return LlamaSession._(lib,h); }
    finally { calloc.free(m); calloc.free(p); }
  }
  static String _error(ffi.DynamicLibrary lib)=>lib.lookupFunction<ffi.Pointer<Utf8> Function(),ffi.Pointer<Utf8> Function()>('lc_error')().toDartString();
  String transcribe(Float32List pcm) {
    final data=calloc<ffi.Float>(pcm.length);
    try { data.asTypedList(pcm.length).setAll(0,pcm);
      final fn=lib.lookupFunction<ffi.Pointer<Utf8> Function(ffi.Pointer<ffi.Void>,ffi.Pointer<ffi.Float>,ffi.Int32),ffi.Pointer<Utf8> Function(ffi.Pointer<ffi.Void>,ffi.Pointer<ffi.Float>,int)>('lc_transcribe');
      final result=fn(handle,data,pcm.length); if(result==ffi.nullptr) throw StateError(_error(lib)); return result.toDartString().trim();
    } finally { calloc.free(data); }
  }
  double timing(int i)=>lib.lookupFunction<ffi.Double Function(ffi.Pointer<ffi.Void>,ffi.Int32),double Function(ffi.Pointer<ffi.Void>,int)>('lc_timing')(handle,i);
  void close()=>lib.lookupFunction<ffi.Void Function(ffi.Pointer<ffi.Void>),void Function(ffi.Pointer<ffi.Void>)>('lc_close')(handle);
}
