package com.autoscript.runtime.api;

import com.autoscript.runtime.api.RecordingReply;

oneway interface IInputRecordingListener {
    void onFinished(in RecordingReply reply);
}
