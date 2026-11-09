#include <jni.h>
#include "SynthEngine.h"

static hichord::SynthEngine gEngine;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hichord_pocket_audio_NativeAudioBridge_startEngine(JNIEnv*, jobject) {
    return gEngine.start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_hichord_pocket_audio_NativeAudioBridge_stopEngine(JNIEnv*, jobject) {
    gEngine.stop();
}

JNIEXPORT void JNICALL
Java_com_hichord_pocket_audio_NativeAudioBridge_noteOn(JNIEnv*, jobject, jint midiNote, jfloat velocity) {
    gEngine.noteOn(static_cast<int>(midiNote), static_cast<float>(velocity));
}

JNIEXPORT void JNICALL
Java_com_hichord_pocket_audio_NativeAudioBridge_noteOff(JNIEnv*, jobject, jint midiNote) {
    gEngine.noteOff(static_cast<int>(midiNote));
}

JNIEXPORT void JNICALL
Java_com_hichord_pocket_audio_NativeAudioBridge_setChordNotes(
    JNIEnv* env,
    jobject,
    jintArray notesArray,
    jfloat velocity
) {
    if (!notesArray) return;
    const jsize len = std::min(env->GetArrayLength(notesArray), hichord::kMaxVoices);
    jint notes[hichord::kMaxVoices]{};
    env->GetIntArrayRegion(notesArray, 0, len, notes);
    if (env->ExceptionCheck()) return;
    gEngine.setChordNotes(notes, len, velocity);
}

JNIEXPORT void JNICALL
Java_com_hichord_pocket_audio_NativeAudioBridge_allNotesOff(JNIEnv*, jobject) {
    gEngine.allNotesOff();
}

JNIEXPORT void JNICALL
Java_com_hichord_pocket_audio_NativeAudioBridge_applyPreset(JNIEnv*, jobject, jint index) {
    gEngine.applyPreset(static_cast<int>(index));
}

JNIEXPORT void JNICALL
Java_com_hichord_pocket_audio_NativeAudioBridge_setFilterCutoff(JNIEnv*, jobject, jfloat cutoffHz) {
    gEngine.setFilterCutoff(cutoffHz);
}

JNIEXPORT void JNICALL
Java_com_hichord_pocket_audio_NativeAudioBridge_setFilterResonance(JNIEnv*, jobject, jfloat resonance) {
    gEngine.setFilterResonance(resonance);
}

JNIEXPORT void JNICALL
Java_com_hichord_pocket_audio_NativeAudioBridge_setReverbMix(JNIEnv*, jobject, jfloat mix) {
    gEngine.setReverbMix(mix);
}

JNIEXPORT void JNICALL
Java_com_hichord_pocket_audio_NativeAudioBridge_setDelayMix(JNIEnv*, jobject, jfloat mix) {
    gEngine.setDelayMix(mix);
}

JNIEXPORT void JNICALL
Java_com_hichord_pocket_audio_NativeAudioBridge_setPitchBend(JNIEnv*, jobject, jfloat semitones) {
    gEngine.setPitchBendSemitones(semitones);
}

JNIEXPORT void JNICALL
Java_com_hichord_pocket_audio_NativeAudioBridge_setVibrato(JNIEnv*, jobject, jfloat depth) {
    gEngine.setVibratoDepth(depth);
}

JNIEXPORT jdouble JNICALL
Java_com_hichord_pocket_audio_NativeAudioBridge_getLatencyMillis(JNIEnv*, jobject) {
    return gEngine.getLatencyMillis();
}

} // extern "C"
