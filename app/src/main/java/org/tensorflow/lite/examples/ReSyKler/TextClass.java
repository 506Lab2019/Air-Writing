package org.tensorflow.lite.examples.ReSyKler;
import com.google.gson.annotations.SerializedName;

public class TextClass {

    @SerializedName(value = "rtext")
    private String rText;

    @SerializedName(value = "response")
    private String Response;

    public String getResponse() {
        return Response;
    }
}
