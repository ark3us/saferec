package net.ark3us.saferec.data;

import androidx.lifecycle.MutableLiveData;

public class LiveData {
    private static LiveData instance;
    private final MutableLiveData<String> statusData = new MutableLiveData<>();

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
}
