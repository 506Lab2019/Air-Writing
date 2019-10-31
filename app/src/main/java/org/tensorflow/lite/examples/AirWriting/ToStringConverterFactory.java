package org.tensorflow.lite.examples.AirWriting;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Converter;

public final class ToStringConverterFactory  extends Converter.Factory {
    public Converter<ResponseBody, ?> fromResponseBody(Type type, Annotation[] annotations) {
        //noinspection EqualsBetweenInconvertibleTypes
        if (String.class.equals(type)) {
            return new Converter<ResponseBody, Object>() {

                @Override
                public Object convert(ResponseBody responseBody) throws IOException {
                    return responseBody.string();
                }
            };
        }

        return null;
    }

    public Converter<?, RequestBody> toRequestBody(Type type, Annotation[] annotations) {
        //noinspection EqualsBetweenInconvertibleTypes
        if (String.class.equals(type)) {
            return new Converter<String, RequestBody>() {

                @Override
                public RequestBody convert(String value) throws IOException {
                    return RequestBody.create(MediaType.parse("text/plain"), value);
                }
            };
        }

        return null;
    }
}