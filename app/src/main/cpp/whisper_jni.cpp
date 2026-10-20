#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <algorithm>
#include "whisper.h"
static void fail(JNIEnv* e, const char* s) { e->ThrowNew(e->FindClass("java/lang/IllegalStateException"), s); }
extern "C" JNIEXPORT jlong JNICALL Java_com_example_callscheduler_data_ai_WhisperNative_open(JNIEnv* e,jobject,jstring path) {
 const char* p=e->GetStringUTFChars(path,nullptr);
 auto params=whisper_context_default_params(); params.use_gpu=false;
 auto* ctx=whisper_init_from_file_with_params(p,params); e->ReleaseStringUTFChars(path,p);
 if(ctx && !whisper_is_multilingual(ctx)){whisper_free(ctx);ctx=nullptr;fail(e,"Use multilingual Whisper base/small, not .en");}
 if(!ctx && !e->ExceptionCheck()) fail(e,"Whisper model initialization failed"); return reinterpret_cast<jlong>(ctx);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_example_callscheduler_data_ai_WhisperNative_run(JNIEnv* e,jobject,jlong handle,jfloatArray audio) {
 auto* ctx=reinterpret_cast<whisper_context*>(handle); if(!ctx){fail(e,"Missing Whisper context");return nullptr;}
 const int n=e->GetArrayLength(audio); std::vector<float> samples(n); e->GetFloatArrayRegion(audio,0,n,samples.data());
 auto p=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
 p.n_threads=std::max(1,std::min(4,(int)std::thread::hardware_concurrency())); p.language="ko";
 p.translate=false;p.no_context=true;p.no_timestamps=true;p.print_progress=false;p.print_realtime=false;p.print_timestamps=false;p.print_special=false;
 if(whisper_full(ctx,p,samples.data(),n)!=0){fail(e,"Whisper transcription failed");return nullptr;}
 std::string out;for(int i=0;i<whisper_full_n_segments(ctx);++i){out+=whisper_full_get_segment_text(ctx,i);out+='\n';}
 return e->NewStringUTF(out.c_str());
}
extern "C" JNIEXPORT void JNICALL Java_com_example_callscheduler_data_ai_WhisperNative_close(JNIEnv*,jobject,jlong h){if(h)whisper_free(reinterpret_cast<whisper_context*>(h));}
