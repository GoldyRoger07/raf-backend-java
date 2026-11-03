package com.raikiservice.raf.question.web.dto;

/**
 * Questions voisines dans la liste du back-office : meme categorie, meme statut,
 * les plus recentes d'abord.
 *
 * @param previousId question affichee juste avant dans la liste (plus recente), ou null
 * @param nextId     question affichee juste apres (plus ancienne), ou null
 * @param position   rang de la question courante, a partir de 1
 * @param total      nombre de questions de la categorie dans ce statut
 */
public record QuestionNeighborsResponse(Long previousId, Long nextId, long position, long total) {
}
