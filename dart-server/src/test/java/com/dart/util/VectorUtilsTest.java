package com.dart.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorUtilsTest {

    @Test
    void encodeToBase64_roundTrip_restoresFloats() {
        float[] original = {0.1f, -0.5f, 0.0f, 1.0f, 3.14f};
        String encoded = VectorUtils.encodeToBase64(original);
        assertThat(VectorUtils.decodeFromBase64(encoded)).isEqualTo(original);
    }

    @Test
    void decodeFromBase64_invalidBase64_throws() {
        assertThatThrownBy(() -> VectorUtils.decodeFromBase64("not-base64!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encodeToBase64_emptyArray_roundTrips() {
        assertThat(VectorUtils.decodeFromBase64(VectorUtils.encodeToBase64(new float[0])))
                .isEmpty();
    }
}
