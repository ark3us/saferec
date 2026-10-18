package net.ark3us.saferec.data;

import androidx.lifecycle.MutableLiveData;

public class LiveData {
    public static final String ERROR_AUTH = "AUTH";
    public static final String ERROR_RECORDING = "RECORDING";

    private static LiveData instance;
    private final MutableLiveData<String> statusData = new MutableLiveData<>();
    // Errors are one-shot events kept apart from the status, so that an upload
    // failure is never mistaken for "recording stopped".
    private final MutableLiveData<String> errorData = new MutableLiveData<>();

    private LiveData() { }

    public static synchronized LiveData getInstance() {
        if (instance == null) {
            instance = new LiveData();
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
}
