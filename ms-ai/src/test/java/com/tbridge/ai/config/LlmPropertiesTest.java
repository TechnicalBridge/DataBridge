package com.tbridge.ai.config;
import com.tbridge.ai.service.LlmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class LlmPropertiesTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(LlmConfig.class)
            .withBean(LlmService.class);
    @ParameterizedTest @ValueSource(strings={"groq", "gemini", "xai"})
    void provider_key_and_model_are_bound(String provider) {
        runner.withPropertyValues("app.llm.provider="+provider,
                "app.llm."+provider+".api-key=SECRET-MARKER-DO-NOT-LEAK",
                "app.llm."+provider+".base-url=http://127.0.0.1:1/v1", "app.llm."+provider+".model=test-model")
            .run(context -> {
                assertNull(context.getStartupFailure()); var llm=context.getBean(LlmService.class);
                assertTrue(llm.configurado()); assertEquals(provider,llm.proveedor()); assertEquals("test-model",llm.modelo());
                assertFalse(context.getBean(LlmProperties.class).toString().contains("SECRET-MARKER"));
                assertFalse(context.getBean(LlmProperties.class).resolve().toString().contains("SECRET-MARKER"));
            });
    }
    @ParameterizedTest @ValueSource(strings={"groq", "gemini", "xai"})
    void missing_key_starts_with_fallback(String provider) {
        runner.withPropertyValues("app.llm.provider="+provider, "app.llm."+provider+".base-url=http://127.0.0.1:1/v1", "app.llm."+provider+".model=test-model")
            .run(context -> {assertNull(context.getStartupFailure());assertFalse(context.getBean(LlmService.class).configurado());assertEquals(provider,context.getBean(LlmService.class).proveedor());});
    }
    @ParameterizedTest @ValueSource(strings={"groq", "gemini", "xai"})
    void missing_model_is_not_configured(String provider) {
        runner.withPropertyValues("app.llm.provider="+provider,"app.llm."+provider+".api-key=ficticia","app.llm."+provider+".base-url=http://127.0.0.1:1/v1")
            .run(context -> {assertNull(context.getStartupFailure());assertFalse(context.getBean(LlmService.class).configurado());});
    }
    @Test void default_local_never_chooses_an_available_key() {
        runner.withPropertyValues("app.llm.groq.api-key=SECRET-MARKER-DO-NOT-LEAK", "app.llm.groq.model=test-model")
            .run(context->{assertNull(context.getStartupFailure());assertEquals("local",context.getBean(LlmService.class).proveedor());assertFalse(context.getBean(LlmService.class).configurado());});
    }
    @Test void unknown_provider_fails_fast() {
        runner.withPropertyValues("app.llm.provider=typo").run(context->assertNotNull(context.getStartupFailure()));
    }
    @Test void unsafe_metadata_and_url_fail_with_generic_messages() {
        assertThrows(IllegalArgumentException.class,()->new LlmProperties("groq",new LlmProperties.Endpoint("secret","https://host/v1?key=secret","model"),null,null,null).resolve());
        assertThrows(IllegalArgumentException.class,()->new LlmProperties("groq",new LlmProperties.Endpoint("secret","https://host/v1","model\nunsafe"),null,null,null).resolve());
    }
}
