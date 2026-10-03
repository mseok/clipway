package dev.mseok.clipway;

oneway interface IClipboardCallback {
    void onClipboardChanged(String text, boolean sensitive);
}
