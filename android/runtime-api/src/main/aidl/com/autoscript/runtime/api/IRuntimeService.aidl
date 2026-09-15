package com.autoscript.runtime.api;

import android.os.ParcelFileDescriptor;
import com.autoscript.runtime.api.IRuntimeStateListener;
import com.autoscript.runtime.api.ScriptValidationReply;
import com.autoscript.runtime.api.VisualCompileReply;

interface IRuntimeService {
    int getProtocolVersion();
    long getSessionGeneration();
    int getRuntimeStateCode();
    int getRootStateCode();
    void registerStateListener(IRuntimeStateListener listener);
    void unregisterStateListener(IRuntimeStateListener listener);
    ScriptValidationReply validateScript(long requestId, long expectedGeneration, String chunkName, in byte[] source);
    VisualCompileReply validateVisualDraft(long requestId, long expectedGeneration, String projectId, String flowId, in ParcelFileDescriptor draftFile);
    VisualCompileReply compileVisualProject(long requestId, long expectedGeneration, String projectId);
    int prepareProject(long requestId, long expectedGeneration);
    int registerTemplate(long requestId, long expectedGeneration, String assetPath, in ParcelFileDescriptor imageFile);
    int registerDictionary(long requestId, long expectedGeneration, String resourcePath, in ParcelFileDescriptor dictionaryFile);
    int startScript(long requestId, long expectedGeneration, in byte[] generatedLuaModule, int designWidth, int designHeight, int scaleMode, in String[] capabilities);
    int requestPause(long requestId, long expectedGeneration);
    int requestResume(long requestId, long expectedGeneration);
    int requestStop(long requestId, long expectedGeneration);
    int setFloatingControlEnabled(long requestId, long expectedGeneration, boolean enabled);
    String[] getRecentRuntimeLogs(long expectedGeneration, int maximumEntries);
}
