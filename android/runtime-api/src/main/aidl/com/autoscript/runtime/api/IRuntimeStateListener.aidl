package com.autoscript.runtime.api;

interface IRuntimeStateListener {
    void onRuntimeStateChanged(long sessionGeneration, int stateCode, int rootStateCode, String diagnostic);
}
