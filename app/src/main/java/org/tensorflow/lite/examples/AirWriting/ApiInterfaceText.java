package org.tensorflow.lite.examples.AirWriting;

import retrofit2.Call;
import retrofit2.http.POST;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;

public interface ApiInterfaceText {

    @FormUrlEncoded
    @POST("download.php")
    Call<String> downloadText(@Field("rtext") String rtext);

}
