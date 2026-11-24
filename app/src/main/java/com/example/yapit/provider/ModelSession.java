package com.example.yapit.provider;

import android.content.Context;
import com.example.yapit.model.*;
import java.io.File;

/** A loaded model/remote-client session scoped to one worker execution. */
public interface ModelSession extends AutoCloseable {
    EnrollmentResult enroll(VoiceProfile profile, File sample, ProgressSink progress) throws Exception;
    GenerationResult generate(VoiceProfile profile, String enrollmentId, GenerationRecord request,
                              File destination, ProgressSink progress) throws Exception;
    @Override default void close() throws Exception { }
}
