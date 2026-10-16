package com.autoscript.runtime.api;

oneway interface IVisionPreparationListener {
    void onFinished(long requestId, long generation, int status, String component, String state);
}
