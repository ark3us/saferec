package net.ark3us.saferec.data;

import androidx.lifecycle.MutableLiveData;

/**
 * Recording status and one-shot errors of SafeRecService, observed by the UI. Holds androidx
 * MutableLiveData, but it is not one: the old name "LiveData" hid that.
 */
public class ServiceState {
    public static final String ERROR_AUTH = "AUTH";
    public static final String ERROR_CAMERA = "CAMERA";
    public static final String ERROR_RECORDING = "RECORDING";

    private static ServiceState instance;
    private final MutableLiveData<String> statusData = new MutableLiveData<>();
    // Errors are one-shot events kept apart from the status, so that an upload
    // failure is never mistaken for "recording stopped".
    private final MutableLiveData<String> errorData = new MutableLiveData<>();

    // Chunks of the current session already on Drive
    private final MutableLiveData<Integer> safeChunks = new MutableLiveData<>(0);

    private ServiceState() { }

    public static synchronized ServiceState getInstance() {
        if (instance == null) {
            instance = new ServiceState();
        }
        return instance;
    }

    public void updateStatus(String status) {
        statusData.postValue(status);
    }

    public MutableLiveData<String> getStatus() {
        return statusData;
    }

    public void reportError(String error) {
        errorData.postValue(error);
    }

    /** Marks the last error as handled so it is not shown again. Main thread only. */
    public void clearError() {
        errorData.setValue(null);
    }

    public MutableLiveData<String> getError() {
        return errorData;
    }

    public MutableLiveData<Integer> getSafeChunks() {
        return safeChunks;
    }

    public void setSafeChunks(int count) {
        safeChunks.postValue(count);
    }
}
