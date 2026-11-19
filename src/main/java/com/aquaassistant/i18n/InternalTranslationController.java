package com.aquaassistant.i18n;

import com.aquaassistant.security.InternalKey;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The backend's own texts (emails, push notices) in the person's language. Only for the backend, with the internal
 * key; these texts do not count against the per-address budget.
 */
@RestController
public class InternalTranslationController {

    private final TranslationService translations;
    private final InternalKey internalKey;

    public InternalTranslationController(TranslationService translations, InternalKey internalKey) {
        this.translations = translations;
        this.internalKey = internalKey;
    }

    @PostMapping("/api/v1/internal/translate")
    public I18nController.TranslateResponse translate(@Valid @RequestBody I18nController.TranslateRequest request,
                                                      @RequestHeader(value = InternalKey.HEADER, required = false) String key) {
        internalKey.check(key);
        String code = I18nController.supported(request.lang());
        return new I18nController.TranslateResponse(code, translations.translateAll(code, request.texts()));
    }
}
