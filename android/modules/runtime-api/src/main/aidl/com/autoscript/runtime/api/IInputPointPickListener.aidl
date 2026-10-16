package com.autoscript.runtime.api;
import com.autoscript.runtime.api.InputPointPickReply;
oneway interface IInputPointPickListener {
    void onFinished(in InputPointPickReply reply);
}
