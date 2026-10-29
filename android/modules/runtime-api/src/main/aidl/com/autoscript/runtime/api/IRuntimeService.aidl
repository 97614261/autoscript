package com.autoscript.runtime.api;

import android.os.ParcelFileDescriptor;
import com.autoscript.runtime.api.IRuntimeStateListener;
import com.autoscript.runtime.api.ScriptValidationReply;
import com.autoscript.runtime.api.VisualCompileReply;
import com.autoscript.runtime.api.TemplateMatchReply;
import com.autoscript.runtime.api.RecognitionPreviewReply;
import com.autoscript.runtime.api.RecordingReply;
import com.autoscript.runtime.api.RuntimeDebugReply;
import com.autoscript.runtime.api.IInputPointPickListener;
import com.autoscript.runtime.api.IInputRecordingListener;
import com.autoscript.runtime.api.IVisionPreparationListener;

interface IRuntimeService {
    int getProtocolVersion();
    TemplateMatchReply testTemplate(long expectedGeneration, in ParcelFileDescriptor frameFile, in ParcelFileDescriptor templateFile, int tolerance, int similarityPermille);
    long getSessionGeneration();
    int getRuntimeStateCode();
    int getRootStateCode();
    int getInputFeatures(long expectedGeneration);
    /** Returns bounded kind=status entries; does not claim recognition success. */
    String[] getVisionAvailability();
    void registerStateListener(IRuntimeStateListener listener);
    void unregisterStateListener(IRuntimeStateListener listener);
    ScriptValidationReply validateScript(long requestId, long expectedGeneration, String chunkName, in byte[] source);
    VisualCompileReply validateVisualDraft(long requestId, long expectedGeneration, String projectId, String flowId, in ParcelFileDescriptor draftFile);
    VisualCompileReply compileVisualProject(long requestId, long expectedGeneration, String projectId);
    VisualCompileReply compileVisualNodeTest(long requestId, long expectedGeneration, String projectId, String flowId, String nodeId);
    int prepareProject(long requestId, long expectedGeneration);
    int configureScriptUi(long expectedGeneration, String projectId, String definitionJson, String valuesJson, in String[] imagePaths, in String[] imageFiles);
    int registerTemplate(long requestId, long expectedGeneration, String assetPath, in ParcelFileDescriptor imageFile);
    int registerDictionary(long requestId, long expectedGeneration, String resourcePath, in ParcelFileDescriptor dictionaryFile);
    int startScript(long requestId, long expectedGeneration, String projectId, in byte[] generatedLuaModule, int designWidth, int designHeight, int scaleMode, in String[] capabilities);
    int requestPause(long requestId, long expectedGeneration);
    int requestResume(long requestId, long expectedGeneration);
    int requestStep(long requestId, long expectedGeneration);
    RuntimeDebugReply getDebugSnapshot(long expectedGeneration, String projectId);
    int requestStop(long requestId, long expectedGeneration);
    int setFloatingControlEnabled(long requestId, long expectedGeneration, boolean enabled);
    int setCaptureOverlayEnabled(long requestId, long expectedGeneration, String projectId, boolean enabled);
    ParcelFileDescriptor capturePreview(long requestId, long expectedGeneration);
    String[] getRecentRuntimeLogs(long expectedGeneration, int maximumEntries);
    int beginInputPointPick(long requestId, long expectedGeneration, String projectId, String flowId, String action, IInputPointPickListener listener);
    int cancelInputPointPick(long requestId, long expectedGeneration);
    RecognitionPreviewReply testRecognitionPreview(long requestId, long expectedGeneration, int operation, in ParcelFileDescriptor frameFile, in @nullable ParcelFileDescriptor assetFile, in int[] options);
    int cancelRecognitionPreview(long requestId, long expectedGeneration);
    RecordingReply startInputRecording(long requestId, long expectedGeneration, long recordingId, String projectId, String flowId, int maxDurationMs, int eventBudget);
    RecordingReply finishInputRecording(long requestId, long expectedGeneration, long recordingId);
    int cancelInputRecording(long requestId, long expectedGeneration, long recordingId);
    VisualCompileReply validateVisualDraftSet(long requestId, long expectedGeneration, String projectId, in ParcelFileDescriptor draftFile);
    int registerAudio(long requestId, long expectedGeneration, String resourcePath, in ParcelFileDescriptor audioFile);
    int configureFloatingResources(long expectedGeneration, boolean enabled);
    RecordingReply startObservedInputRecording(long requestId, long expectedGeneration, long recordingId, String projectId, String flowId, int maxDurationMs, int eventBudget, IInputRecordingListener listener);
    int beginVisionPreparation(long requestId, long expectedGeneration, String sourceDigest, in String[] components, IVisionPreparationListener listener);
    int cancelVisionPreparation(long requestId, long expectedGeneration);
    boolean isVisionPreparationActive();
    /** Explicit user demand; status reads never start or retry Root authorization. */
    int requestRootConnection(long expectedGeneration);
}
