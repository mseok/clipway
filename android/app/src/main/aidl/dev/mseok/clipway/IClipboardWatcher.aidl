package dev.mseok.clipway;

import dev.mseok.clipway.IClipboardCallback;

// Implemented by ClipboardWatcherService, which Shizuku runs with shell privileges.
interface IClipboardWatcher {
    // Method id reserved by Shizuku: called when the service should exit.
    void destroy() = 16777114;

    void watch(IClipboardCallback callback) = 1;
}
