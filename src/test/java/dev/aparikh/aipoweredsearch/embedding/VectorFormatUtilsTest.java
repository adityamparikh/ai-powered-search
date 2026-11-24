package dev.aparikh.aipoweredsearch.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.junit.jupiter.api.Test;

class VectorFormatUtilsTest {

  @Test
  void formatsAnArrayAndAListTheSameWay() {
    float[] vector = {0.1f, -0.2f, 3f};

    assertThat(VectorFormatUtils.formatVectorForSolr(vector)).isEqualTo("[0.1, -0.2, 3.0]");
    assertThat(VectorFormatUtils.formatVectorForSolr(List.of(0.1f, -0.2f, 3f)))
        .isEqualTo("[0.1, -0.2, 3.0]");
  }

  @Test
  void formatsAnEmptyVector() {
    assertThat(VectorFormatUtils.formatVectorForSolr(new float[0])).isEqualTo("[]");
    assertThat(VectorFormatUtils.formatVectorForSolr(List.of())).isEqualTo("[]");
  }

  @Test
  void convertsAnArrayToAList() {
    assertThat(VectorFormatUtils.convertToList(new float[] {1f, 2f})).containsExactly(1f, 2f);
  }

  @Test
  void cannotBeInstantiated() throws Exception {
    Constructor<VectorFormatUtils> constructor = VectorFormatUtils.class.getDeclaredConstructor();
    constructor.setAccessible(true);

    assertThatThrownBy(constructor::newInstance)
        .isInstanceOf(InvocationTargetException.class)
        .cause()
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
