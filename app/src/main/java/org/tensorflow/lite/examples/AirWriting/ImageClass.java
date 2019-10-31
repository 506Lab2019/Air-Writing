package org.tensorflow.lite.examples.ReSyKler;
import com.google.gson.annotations.SerializedName;

public class ImageClass {

    @SerializedName(value = "title")
    private String Title;

    @SerializedName(value = "image")
    private String Image;

    @SerializedName(value = "response")
    private String Response;

    public String getResponse() {
        return Response;
    }
}
