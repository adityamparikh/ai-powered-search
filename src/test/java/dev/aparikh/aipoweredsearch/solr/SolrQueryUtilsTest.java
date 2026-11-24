package dev.aparikh.aipoweredsearch.solr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;

class SolrQueryUtilsTest {

  @Test
  void buildsAKnnQuery() {
    assertThat(SolrQueryUtils.buildKnnQuery("emb", 5, "[1.0, 2.0]"))
        .isEqualTo("{!knn f=emb topK=5}[1.0, 2.0]");
  }

  @Test
  void defaultsToTheVectorField() {
    assertThat(SolrQueryUtils.buildKnnQuery(3, "[1.0]")).isEqualTo("{!knn f=vector topK=3}[1.0]");
  }

  @Test
  void rejectsInvalidArguments() {
    assertThatThrownBy(() -> SolrQueryUtils.buildKnnQuery(" ", 1, "[1.0]"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("field name");
    assertThatThrownBy(() -> SolrQueryUtils.buildKnnQuery("vector", 0, "[1.0]"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("topK");
    assertThatThrownBy(() -> SolrQueryUtils.buildKnnQuery("vector", 1, nullString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Vector string");
  }

  @Test
  void cannotBeInstantiated() throws Exception {
    Constructor<SolrQueryUtils> constructor = SolrQueryUtils.class.getDeclaredConstructor();
    constructor.setAccessible(true);

    assertThatThrownBy(constructor::newInstance)
        .isInstanceOf(InvocationTargetException.class)
        .cause()
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @SuppressWarnings({"NullAway", "DataFlowIssue"})
  private static String nullString() {
    return null;
  }
}
