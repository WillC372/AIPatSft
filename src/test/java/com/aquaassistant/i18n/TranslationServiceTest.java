package com.aquaassistant.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aquaassistant.intelligence.ai.AiAnswer;
import com.aquaassistant.intelligence.ai.AiPrompt;
import com.aquaassistant.intelligence.ai.AiUnavailableException;
import com.aquaassistant.intelligence.ai.FallbackAiProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TranslationServiceTest {

    private UiTranslationRepository repository;
    private FallbackAiProvider ai;
    private TranslationService service;

    @BeforeEach
    void setUp() {
        repository = mock(UiTranslationRepository.class);
        when(repository.findByLang(any())).thenReturn(List.of());
        ai = mock(FallbackAiProvider.class);
        service = new TranslationService(repository, ai, new ObjectMapper(), new TranslationBudget(1000, 100000));
    }

    private void aiAnswers(String json) {
        when(ai.complete(any(AiPrompt.class))).thenReturn(new AiAnswer(json, "Google Gemini", "test", 10, 10, false));
    }

    @Test
    void translatesOnceAndThenUsesTheStoredTranslation() {
        aiAnswers("{\"t\":[\"Places\",\"Use <0>My water</0> as the first place.\"]}");
        List<String> first = service.translate("en", List.of("Lugares", "Usa <0>Mi agua</0> como primer lugar.", "7.25"), "1.2.3.4");
        assertThat(first).containsExactly("Places", "Use <0>My water</0> as the first place.", "7.25");
        List<String> again = service.translate("en", List.of("Lugares"), "1.2.3.4");
        assertThat(again).containsExactly("Places");
        verify(ai, times(1)).complete(any(AiPrompt.class));
        verify(repository, times(2)).saveAndFlush(any(UiTranslation.class));
    }

    @Test
    void discardsTranslationsThatLoseTheMarkers() {
        aiAnswers("{\"t\":[\"Use My water as the first place.\"]}");
        List<String> out = service.translate("en", List.of("Usa <0>Mi agua</0> como primer lugar."), "1.2.3.4");
        assertThat(out).containsExactly((String) null);
        verify(repository, times(0)).saveAndFlush(any(UiTranslation.class));
    }

    @Test
    void numberMarkersMustSurvive() {
        assertThat(TranslationService.leftInSpanish("¿Qué quieres ajustar?", "¿Qué quieres ajustar?")).isTrue();
        assertThat(TranslationService.leftInSpanish("Ácido cianúrico", "ácido cianúrico ")).isTrue();
        assertThat(TranslationService.leftInSpanish("¿Qué quieres ajustar?", "Cosa vuoi regolare?")).isFalse();
        assertThat(TranslationService.leftInSpanish("Español", "Español")).isFalse();
        assertThat(TranslationService.leftInSpanish("pH {0}", "pH {0}")).isFalse();
        assertThat(TranslationService.sameMarkers("Cada {0} días", "Every {0} days")).isTrue();
        assertThat(TranslationService.sameMarkers("Cada {0} días", "Every day")).isFalse();
        assertThat(TranslationService.sameMarkers("<0>{0}</0> de {1}", "{1} の <0>{0}</0>")).isTrue();
    }

    @Test
    void translationsThatAreStillSpanishAreRecognized() {
        assertThat(TranslationService.stillSpanish("it", "¿Qué aplicaste o hiciste?", "¿Qué ha aplicado o ha hecho?")).isTrue();
        assertThat(TranslationService.stillSpanish("it", "Ác. cianúrico", "Ácido cianúrico")).isTrue();
        assertThat(TranslationService.stillSpanish("ja", "Destacada estación", "特集 estación")).isTrue();
        assertThat(TranslationService.stillSpanish("it", "Ya hay un informe en curso.", "C'è già un relatório in corso.")).isTrue();
        // names of people or places are kept as they are
        assertThat(TranslationService.stillSpanish("en", "Río de Bogotá", "Bogotá river")).isFalse();
        assertThat(TranslationService.stillSpanish("fr", "Mi piscina", "Ma piscine")).isFalse();
        assertThat(TranslationService.stillSpanish("de", "Español", "Español")).isFalse();
        assertThat(TranslationService.stillSpanish("it", "Cosa", "perché")).isFalse();
        assertThat(TranslationService.stillSpanish("es", "Qué", "¿Qué?")).isFalse();
    }

    @Test
    void aTranslationThatIsStillSpanishIsAskedAgainAndNeverStored() {
        when(ai.complete(any(AiPrompt.class))).thenReturn(
            new AiAnswer("{\"t\":[\"¿Qué ha aplicado o ha hecho?\",\"Acido cianurico\"]}", "Google Gemini", "test", 10, 10, false),
            new AiAnswer("{\"t\":[\"Cosa hai applicato o fatto?\"]}", "Google Gemini", "test", 10, 10, false));
        List<String> out = service.translate("it", List.of("¿Qué aplicaste o hiciste?", "Ác. cianúrico"), "1.2.3.4");
        assertThat(out).containsExactly("Cosa hai applicato o fatto?", "Acido cianurico");
        ArgumentCaptor<AiPrompt> prompts = ArgumentCaptor.forClass(AiPrompt.class);
        verify(ai, times(2)).complete(prompts.capture());
        assertThat(prompts.getAllValues().get(1).system()).contains("Spanish words");
    }

    @Test
    void aTranslationStillSpanishAfterAskingAgainIsNotStored() {
        aiAnswers("{\"t\":[\"Ácido cianúrico\"]}");
        List<String> out = service.translate("it", List.of("Ác. cianúrico"), "1.2.3.4");
        assertThat(out).containsExactly((String) null);
        verify(repository, times(0)).saveAndFlush(any(UiTranslation.class));
    }

    @Test
    void serverTextsShareOneTranslationWhateverTheNumbers() {
        aiAnswers("{\"t\":[\"Measurements: {0}. Average: {1}.\"]}");
        assertThat(service.translateAll("en", List.of("Mediciones: 4. Promedio: 7.65.")))
            .containsExactly("Measurements: 4. Average: 7.65.");
        assertThat(service.translateAll("en", List.of("Mediciones: 9. Promedio: 6.10.")))
            .containsExactly("Measurements: 9. Average: 6.10.");
        verify(ai, times(1)).complete(any(AiPrompt.class));
    }

    @Test
    void textsWithAnEmailAddressAreNeverSentNorStored() {
        List<String> out = service.translate("fr", List.of("persona@example.com", "Escribe ana.lopez@example.com para confirmar"), "1.2.3.4");
        assertThat(out).containsExactly("persona@example.com", "Escribe ana.lopez@example.com para confirmar");
        verify(ai, times(0)).complete(any(AiPrompt.class));
        verify(repository, times(0)).saveAndFlush(any(UiTranslation.class));
    }

    @Test
    void aTranslationWithAnotherFormOfAddressIsAskedAgain() {
        when(ai.complete(any(AiPrompt.class)))
            .thenReturn(new AiAnswer("{\"t\":[\"Ajoutez votre eau\",\"Lieux\"]}", "Groq", "test", 10, 10, false))
            .thenReturn(new AiAnswer("{\"t\":[\"Ajoute ton eau\"]}", "Groq", "test", 10, 10, false));
        List<String> out = service.translate("fr", List.of("Agrega tu agua", "Lugares"), "1.2.3.4");
        assertThat(out).containsExactly("Ajoute ton eau", "Lieux");
        verify(ai, times(2)).complete(any(AiPrompt.class));
        assertThat(TranslationService.otherTone("de", "Hier siehst du deine Werte")).isFalse();
        assertThat(TranslationService.otherTone("de", "Hier sehen Sie Ihre Werte")).isTrue();
        assertThat(TranslationService.otherTone("de", "Sie sind gespeichert.")).isFalse();
    }

    @Test
    void translationsKeepTheAiQuotaForAquasAnswers() {
        java.time.Instant[] now = {java.time.Instant.parse("2026-11-18T10:00:00Z")};
        service.useClock(() -> now[0], 2);
        aiAnswers("{\"t\":[\"One\"]}");
        service.translate("en", List.of("Uno"), "1.2.3.4");
        assertThat(service.aiAvailable(true)).isFalse();   // the advance translation gets part of the share
        assertThat(service.aiAvailable(false)).isTrue();
        aiAnswers("{\"t\":[\"Two\"]}");
        service.translate("en", List.of("Dos"), "1.2.3.4");
        assertThat(service.translate("en", List.of("Tres"), "1.2.3.4")).containsExactly((String) null);
        verify(ai, times(2)).complete(any(AiPrompt.class));
        now[0] = now[0].plus(java.time.Duration.ofDays(1));
        assertThat(service.aiAvailable(false)).isTrue();

        when(ai.complete(any(AiPrompt.class))).thenThrow(new AiUnavailableException("Groq: límite de uso del plan gratuito alcanzado."));
        service.translate("en", List.of("Cuatro"), "1.2.3.4");
        assertThat(service.aiAvailable(false)).isFalse();
        now[0] = now[0].plus(TranslationService.QUOTA_PAUSE).plusSeconds(1);
        assertThat(service.aiAvailable(false)).isTrue();
    }

    @Test
    void spanishAndUnknownTextsAreNotSentToTheAi() {
        assertThat(service.translate("es", List.of("Hola"), "x")).containsExactly("Hola");
        verify(ai, times(0)).complete(any(AiPrompt.class));
    }

    @Test
    void whenTheAiFailsTheTextStaysUntranslatedAndIsNotStored() {
        when(ai.complete(any(AiPrompt.class))).thenThrow(new AiUnavailableException("sin cuota"));
        assertThat(service.translate("fr", List.of("Medir"), "x")).containsExactly((String) null);
        assertThat(service.translateOne("fr", "Medir")).isEqualTo("Medir");
    }

    @Test
    void theAiIsToldTheLanguageAndToKeepTheMarkers() {
        aiAnswers("{\"t\":[\"Medir\"]}");
        service.translate("de", List.of("Medir"), "x");
        ArgumentCaptor<AiPrompt> prompt = ArgumentCaptor.forClass(AiPrompt.class);
        verify(ai).complete(prompt.capture());
        assertThat(prompt.getValue().system()).contains("German").contains("<0>").contains("never \"Sie\"");
        assertThat(prompt.getValue().purpose()).isEqualTo("TRANSLATION");
        // Strict schemas need additionalProperties=false or Groq rejects the request.
        assertThat(prompt.getValue().responseSchema()).containsEntry("additionalProperties", false);
        assertThat(prompt.getValue().userId()).isNull();
    }

    @Test
    void theBudgetLimitsNewTextsPerAddress() {
        TranslationBudget budget = new TranslationBudget(3, 1000);
        assertThat(budget.take("a", 2)).isEqualTo(2);
        assertThat(budget.take("a", 2)).isEqualTo(1);
        assertThat(budget.take("b", 2)).isEqualTo(2);
        assertThat(budget.take(TranslationBudget.SERVER, 50)).isEqualTo(50);
    }

    @Test
    void languagesAreNormalizedAndDescribed() {
        assertThat(Languages.normalize("ZH-hans")).isEqualTo("zh-Hans");
        assertThat(Languages.normalize("xx")).isNull();
        assertThat(Languages.englishName("zh-Hans")).isEqualTo("Simplified Chinese");
        assertThat(Languages.nativeName("fr")).isEqualTo("Français");
        assertThat(Languages.codes()).containsExactly("es", "en", "fr", "de", "it", "zh-Hans", "ja");
        assertThat(Languages.normalize("pt-BR")).isNull();
        assertThat(Languages.drawableInPdf("ja")).isTrue();
        assertThat(Languages.nativeName("ja")).isEqualTo("日本語");
    }
}
