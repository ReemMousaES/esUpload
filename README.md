# esUpload

A sequential file upload library for Android using WorkManager. Built by **Extreme Solution**.

## Features

- **Sequential uploads** — files are uploaded one at a time via a `Mutex`, ensuring order and preventing server overload
- **Automatic retries** — configurable retry count with exponential backoff
- **Progress tracking** — real-time upload progress reported at 5% intervals via WorkManager's `setProgress()`
- **Persistent queue** — uploads survive process death using Room + WorkManager
- **Hilt integration** — inject `EsUploadManager` anywhere in your app
- **Network-aware** — uploads only run when a network connection is available

## Installation

### JitPack

Add JitPack to your project-level `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency to your app-level `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.ReemMousaES:esUpload:1.0.0")
}
```

## Setup

### 1. Hilt

Your `Application` class must be annotated with `@HiltAndroidApp`.

### 2. WorkManager

Your `Application` must implement `Configuration.Provider` and provide `HiltWorkerFactory`:

```kotlin
@HiltAndroidApp
class MyApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
```

### 3. Disable default WorkManager initializer

In your `AndroidManifest.xml`:

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup"
        tools:node="remove" />
</provider>
```

## Usage

### Enqueue an upload

```kotlin
@Inject
lateinit var esUploadManager: EsUploadManager

esUploadManager.enqueue(
    UploadRequest(
        uploadId = "photo_123",
        filePath = "/storage/.../photo.jpg",
        url = "https://api.example.com/upload",
        headers = mapOf("Authorization" to "Bearer token"),
        params = mapOf("eventId" to "evt_1"),
        fileParamName = "photo"
    )
)
```

### Observe progress

```kotlin
esUploadManager.observeWorkInfo().observe(this) { workInfos ->
    workInfos.forEach { workInfo ->
        when (workInfo.state) {
            WorkInfo.State.RUNNING -> {
                val uploadId = workInfo.progress.getString(FileUploadWorker.KEY_UPLOAD_ID)
                val progress = workInfo.progress.getInt(FileUploadWorker.KEY_PROGRESS, 0)
            }
            WorkInfo.State.SUCCEEDED -> {
                val uploadId = workInfo.outputData.getString(FileUploadWorker.KEY_UPLOAD_ID)
                val body = workInfo.outputData.getString(FileUploadWorker.KEY_RESPONSE_BODY)
            }
            WorkInfo.State.FAILED -> {
                val error = workInfo.outputData.getString(FileUploadWorker.KEY_ERROR_MESSAGE)
            }
            else -> {}
        }
    }
}
```

### Retry / Cancel / Delete

```kotlin
esUploadManager.retry("photo_123")
esUploadManager.cancelAll()
esUploadManager.delete("photo_123")
esUploadManager.deleteAllFailed()
```

## API Reference

| Method | Description |
|---|---|
| `enqueue(request)` | Enqueue a single upload |
| `enqueueAll(requests)` | Enqueue multiple uploads |
| `retry(uploadId)` | Retry a failed upload |
| `cancelAll()` | Cancel all pending/running uploads |
| `delete(uploadId)` | Delete a specific upload |
| `deleteAllFailed()` | Delete all failed uploads |
| `resetStaleUploads()` | Reset stuck UPLOADING → FAILED |
| `observeAll()` | Observe all uploads (LiveData) |
| `observeByStatus(status)` | Observe uploads by status |
| `observePending()` | Observe non-completed uploads |
| `observeCountByStatus(status)` | Observe count by status |
| `observeWorkInfo()` | Observe WorkManager work info |

## License

MIT License — see [LICENSE](LICENSE) for details.
