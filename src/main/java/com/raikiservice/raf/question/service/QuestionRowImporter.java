package com.raikiservice.raf.question.service;

import java.util.List;

import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.Question;
import com.raikiservice.raf.question.domain.QuestionStatus;
import com.raikiservice.raf.question.domain.QuestionType;
import com.raikiservice.raf.question.web.dto.ChoiceDto;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Importe UNE ligne de CSV dans sa propre transaction.
 *
 * <p>Sans cet isolement, une ligne fautive condamnerait tout l'import : la methode
 * de creation rejoindrait la transaction de l'import, son echec la marquerait
 * rollback-only, et le commit final echouerait malgre l'exception attrapee ligne
 * par ligne. Chaque ligne doit donc reussir ou echouer seule.
 *
 * <p>Bean distinct de {@link QuestionImportService} parce qu'un appel interne ne
 * passe pas par le proxy Spring et ignorerait la propagation demandee.
 */
@Component
@RequiredArgsConstructor
public class QuestionRowImporter {

    private final QuestionService questionService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long importRow(Long actorId, Long categoryId, String text, QuestionType type,
            Difficulty difficulty, String explanation, List<ChoiceDto> choices,
            boolean publishDirectly) {

        Question question = questionService.createEntity(actorId, categoryId, text, type,
                difficulty, explanation, null, choices);

        if (publishDirectly) {
            question.setStatus(QuestionStatus.PUBLISHED);
        }

        return question.getId();
    }
}
