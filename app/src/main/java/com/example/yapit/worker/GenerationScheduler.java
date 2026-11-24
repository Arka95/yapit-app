package com.example.yapit.worker;

import android.content.Context;
import androidx.work.*;
import com.example.yapit.model.*;
import java.util.concurrent.TimeUnit;

public final class GenerationScheduler {
    private GenerationScheduler() { }
    public static void enqueue(Context context, GenerationRecord record, ModelMetadata model) {
        Constraints.Builder constraints = new Constraints.Builder().setRequiresStorageNotLow(true);
        if (model.supports(ModelCapability.REQUIRES_NETWORK)) constraints.setRequiredNetworkType(NetworkType.CONNECTED);
        Data input = new Data.Builder().putString(GenerationWorker.KEY_GENERATION_ID, record.generationId).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(GenerationWorker.class)
                .setInputData(input).setConstraints(constraints.build())
                .addTag("yapit-generation").addTag("generation:" + record.generationId)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build();
        WorkManager.getInstance(context).enqueueUniqueWork("generation:" + record.generationId, ExistingWorkPolicy.KEEP, request);
    }
}
