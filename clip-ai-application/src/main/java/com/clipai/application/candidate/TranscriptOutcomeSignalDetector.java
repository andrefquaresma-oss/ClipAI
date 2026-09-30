package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.CandidateSignalType;
import com.clipai.domain.transcript.Transcript;
import com.clipai.domain.transcript.TranscriptSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TranscriptOutcomeSignalDetector {
    private static final Map<String, Map<String, List<String>>> PHRASES = Map.of(
            "en", Map.of("SAVE", List.of("saved by", "good save", "keeper saves", "makes the save"),
                    "BLOCK", List.of("blocked", "blocks the shot"),
                    "CORNER", List.of("corner kick", "corner to", "wins a corner"),
                    "CLEARANCE", List.of("cleared away", "clears the ball", "cleared off the line"),
                    "WIDE", List.of("wide of the goal", "goes wide", "off target"),
                    "POST", List.of("off the post", "hits the post", "off the bar"),
                    "CONTINUED_PLAY", List.of("play continues", "keeps the attack alive"),
                    "PENALTY_AWARDED", List.of("penalty awarded", "given a penalty", "penalty to"),
                    "PENALTY_ATTEMPT", List.of("takes the penalty", "penalty is taken", "penalty kick")),
            "pt", Map.of("SAVE", List.of("boa defesa", "defendeu", "grande defesa"),
                    "BLOCK", List.of("bloqueado", "bloqueia o remate"),
                    "CORNER", List.of("canto para", "pontapé de canto"),
                    "CLEARANCE", List.of("afastou a bola", "cortou a bola"),
                    "WIDE", List.of("ao lado da baliza", "por cima da baliza"),
                    "POST", List.of("ao poste", "na trave"),
                    "CONTINUED_PLAY", List.of("o jogo continua", "mantém o ataque"),
                    "PENALTY_AWARDED", List.of("penalti assinalado", "penalti para"),
                    "PENALTY_ATTEMPT", List.of("cobrança do penalti", "bate o penalti")),
            "es", Map.of("SAVE", List.of("buena parada", "gran parada", "detiene el disparo"),
                    "BLOCK", List.of("bloqueado", "bloquea el disparo"),
                    "CORNER", List.of("saque de esquina", "córner para"),
                    "CLEARANCE", List.of("despeja el balón", "despeja la pelota"),
                    "WIDE", List.of("fuera de la portería", "se marcha fuera"),
                    "POST", List.of("al poste", "al larguero"),
                    "CONTINUED_PLAY", List.of("sigue la jugada", "continúa el ataque"),
                    "PENALTY_AWARDED", List.of("penalti señalado", "penalti para"),
                    "PENALTY_ATTEMPT", List.of("lanza el penalti", "lanzamiento de penalti")),
            "fr", Map.of("SAVE", List.of("bel arrêt", "arrêt du gardien", "repousse le tir"),
                    "BLOCK", List.of("tir contré", "frappe contrée"),
                    "CORNER", List.of("corner pour", "coup de pied de coin"),
                    "CLEARANCE", List.of("dégage le ballon", "dégagement"),
                    "WIDE", List.of("à côté du but", "passe à côté"),
                    "POST", List.of("sur le poteau", "sur la barre"),
                    "CONTINUED_PLAY", List.of("le jeu continue", "l'attaque continue"),
                    "PENALTY_AWARDED", List.of("penalty accordé", "penalty pour"),
                    "PENALTY_ATTEMPT", List.of("tire le penalty", "frappe le penalty")),
            "it", Map.of("SAVE", List.of("grande parata", "para il tiro"),
                    "BLOCK", List.of("tiro murato", "conclusione respinta"),
                    "CORNER", List.of("calcio d'angolo", "corner per"),
                    "CLEARANCE", List.of("allontana il pallone", "spazza via"),
                    "WIDE", List.of("fuori dallo specchio", "va fuori"),
                    "POST", List.of("sul palo", "sulla traversa"),
                    "CONTINUED_PLAY", List.of("l'azione continua", "prosegue l'attacco"),
                    "PENALTY_AWARDED", List.of("calcio di rigore per", "rigore assegnato"),
                    "PENALTY_ATTEMPT", List.of("calcia il rigore", "battuta del rigore")),
            "de", Map.of("SAVE", List.of("starke parade", "hält den schuss"),
                    "BLOCK", List.of("schuss geblockt", "geblockt"),
                    "CORNER", List.of("eckball für", "ecke für"),
                    "CLEARANCE", List.of("klärt den ball", "geklärt"),
                    "WIDE", List.of("geht am tor vorbei", "geht weit vorbei"),
                    "POST", List.of("an den pfosten", "an die latte"),
                    "CONTINUED_PLAY", List.of("das spiel läuft weiter", "der angriff geht weiter"),
                    "PENALTY_AWARDED", List.of("elfmeter für", "strafstoß für"),
                    "PENALTY_ATTEMPT", List.of("schießt den elfmeter", "führt den elfmeter aus"))
    );

    public List<CandidateSignalObservation> detect(Transcript transcript) {
        String language = transcript.getLanguage().toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        Map<String, List<String>> phrases = PHRASES.get(language);
        if (phrases == null) return List.of();
        List<CandidateSignalObservation> observations = new ArrayList<>();
        for (TranscriptSegment segment : transcript.getSegments()) {
            String normalized = TranscriptTextNormalizer.normalize(segment.text());
            for (Map.Entry<String, List<String>> outcome : phrases.entrySet()) {
                outcome.getValue().stream()
                        .filter(phrase -> normalized.contains(TranscriptTextNormalizer.normalize(phrase)))
                        .findFirst()
                        .ifPresent(phrase -> {
                            double confidence = outcome.getKey().equals("CORNER")
                                    || outcome.getKey().startsWith("PENALTY_") ? 0.72 : 0.78;
                            observations.add(new CandidateSignalObservation(segment.startTimeMs(), List.of(
                                    new CandidateSignal(CandidateSignalType.SHOT_OUTCOME_CONTEXT, null,
                                            confidence, segment.startTimeMs(),
                                            "OUTCOME=" + outcome.getKey() + "; transcript phrase match: "
                                                    + phrase + "; segment sequence=" + segment.sequence()))));
                        });
            }
        }
        return List.copyOf(observations);
    }
}
