package dev.mseok.clipway;

import android.os.ParcelFileDescriptor;

oneway interface IClipboardCallback {
    void onClipboardChanged(String text, boolean sensitive);

    // A copied picture: `size` bytes can be read from `data`, a pipe.
    void onImageCopied(in ParcelFileDescriptor data, String mime, int size);
}
