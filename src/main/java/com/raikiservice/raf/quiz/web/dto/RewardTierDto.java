package com.raikiservice.raf.quiz.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Tranche de rangs recompensee. Exemple : (1,1,10000) ou (4,10,500). */
public record RewardTierDto(

        @Min(value = 1, message = "Le rang de debut commence a 1.")
        int rankFrom,

        @Min(value = 1, message = "Le rang de fin commence a 1.")
        int rankTo,

        @Min(value = 1, message = "Le montant doit etre positif.")
        long amountCredits,

        @Size(max = 120)
        String label) {
}
