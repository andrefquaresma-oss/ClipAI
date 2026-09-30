package com.clipai.application.candidate;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public record CandidateDetectionQualitySettings(
        long speechRateWindowMs,
        double speechRateSpikeMultiplier,
        int minimumWordsPerWindow,
        double pitchRiseRatio,
        double pitchVarianceRatio,
        double minimumVoicedFrameRatio,
        double minimumLiveEventProbability,
        double replayProbabilityThreshold,
        long replayTemporalWindowMs,
        long contextAttachWindowMs,
        long boundarySearchBackMs,
        long attackBuildupMaximumLeadMs,
        long goalFallbackPreRollMs,
        double crowdZeroCrossingThreshold,
        long maximumCandidateDurationMs,
        double minimumGoalReactionConfidence,
        double replayPenaltyWeight,
        double goalNegativeContextPenaltyWeight) {

    public CandidateDetectionQualitySettings(long speechRateWindowMs, double speechRateSpikeMultiplier,
                                             int minimumWordsPerWindow, double pitchRiseRatio,
                                             double pitchVarianceRatio, double minimumVoicedFrameRatio,
                                             double minimumLiveEventProbability, double replayProbabilityThreshold,
                                             long replayTemporalWindowMs, long contextAttachWindowMs,
                                             long boundarySearchBackMs, long attackBuildupMaximumLeadMs,
                                             long goalFallbackPreRollMs, double crowdZeroCrossingThreshold,
                                             long maximumCandidateDurationMs, double minimumGoalReactionConfidence,
                                             double replayPenaltyWeight) {
        this(speechRateWindowMs, speechRateSpikeMultiplier, minimumWordsPerWindow, pitchRiseRatio,
                pitchVarianceRatio, minimumVoicedFrameRatio, minimumLiveEventProbability,
                replayProbabilityThreshold, replayTemporalWindowMs, contextAttachWindowMs,
                boundarySearchBackMs, attackBuildupMaximumLeadMs, goalFallbackPreRollMs,
                crowdZeroCrossingThreshold, maximumCandidateDurationMs, minimumGoalReactionConfidence,
                replayPenaltyWeight, 0.45);
    }

    private static final Map<String, List<String>> REPLAY_PHRASES = Map.of(
            "pt", List.of("vamos rever", "rever a jogada", "replay", "na repeticao",
                    "em camera lenta", "vamos ver de novo"),
            "es", List.of("vamos a ver la repeticion", "en la repeticion", "repeticion",
                    "lo vemos otra vez", "camara lenta", "volvemos a ver la jugada"),
            "en", List.of("let us look again", "in the replay", "the replay shows", "watch the replay",
                    "slow motion", "we saw earlier", "as mentioned earlier", "earlier action"),
            "fr", List.of("on revoit", "on va revoir", "revoir tout de suite", "revoyons",
                    "au ralenti", "sur la repetition", "replay",
                    "on a vu plus tot", "comme on l a mentionne", "action precedente"),
            "it", List.of("rivediamo", "al replay", "al rallentatore", "ripetizione",
                    "come abbiamo visto prima", "azione precedente"),
            "de", List.of("in der wiederholung", "noch einmal in der wiederholung",
                    "zeitlupe", "wiederholung", "wie bereits gesehen", "vorherige szene"));
    private static final Map<String, List<String>> RETROSPECTIVE_PHRASES = Map.of(
            "pt", List.of("no lance anterior", "na jogada anterior", "tinha marcado", "ja tinha feito",
                    "gol da temporada", "terceiro gol da temporada", "marcou anteriormente",
                    "o placar era", "resultado final"),
            "es", List.of("en la jugada anterior", "ya habia marcado", "el gol anterior", "anteriormente",
                    "gol de la temporada", "tercer gol de la temporada", "su tercer gol",
                    "el marcador era", "resultado final"),
            "en", List.of("earlier in the match", "the previous goal", "had scored earlier", "last week",
                    "goal of the season", "third goal of the season", "his third goal this season",
                    "as we mentioned earlier", "if i remember correctly", "if i remember right",
                    "the score was", "the scoreline", "final score"),
            "fr", List.of("sur l action precedente", "avait deja marque", "le but precedent", "au match aller",
                    "but de la saison", "troisieme but de la saison", "son troisieme but",
                    "comme on l a dit", "on a vu plus tot", "si je me souviens bien",
                    "si je me rappelle bien", "le score etait", "score final"),
            "it", List.of("nell azione precedente", "aveva gia segnato", "il gol precedente",
                    "gol della stagione", "terzo gol della stagione", "il punteggio era", "risultato finale"),
            "de", List.of("in der vorherigen szene", "hatte bereits getroffen", "das vorherige tor",
                    "tor der saison", "drittes tor der saison", "der spielstand war", "endstand"));
    private static final Map<String, List<String>> BUILDUP_PHRASES = Map.of(
            "pt", List.of("cruza na area", "bola na area", "parte para cima", "avanca pelo lado", "vai finalizar"),
            "es", List.of("centro al area", "balon al area", "encara al defensor", "avanza por la banda"),
            "en", List.of("crosses into the box", "drives into the area", "plays it across", "through on goal"),
            "fr", List.of("centre dans la surface", "ballon dans la surface", "deborde sur le cote", "face au gardien"),
            "it", List.of("cross in area", "entra in area", "avanza sulla fascia", "davanti al portiere"),
            "de", List.of("flanke in den strafraum", "zieht in den strafraum", "vor dem tor", "vor dem torwart"));
    private static final Map<String, List<String>> RESTART_PHRASES = Map.of(
            "pt", List.of("pontape de saida", "bola ao centro", "meio campo", "recomeca o jogo", "jogo recomeça",
                    "a bola volta a rolar"),
            "es", List.of("saque inicial", "saca de centro", "centro del campo", "se reanuda el juego", "vuelve a rodar el balon",
                    "pone el balon en juego"),
            "en", List.of("kickoff", "kick off", "back underway", "play resumes", "restarts play",
                    "the game restarts", "gets us underway", "midfield"),
            "fr", List.of("coup d envoi", "milieu de terrain", "le jeu reprend", "le match reprend",
                    "reprise du jeu", "remet en jeu"),
            "it", List.of("calcio d inizio", "centrocampo", "si riprende il gioco", "riprende la partita", "rimette in gioco"),
            "de", List.of("anpfiff", "anstoss", "das spiel lauft weiter", "das spiel geht weiter",
                    "setzt das spiel fort"));
    private static final Map<String, List<String>> INJURY_PHRASES = Map.of(
            "pt", List.of("jogador lesionado", "atendimento medico", "assistencia medica", "entra a equipa medica"),
            "es", List.of("jugador lesionado", "atencion medica", "asistencia medica", "entra el equipo medico"),
            "en", List.of("injured player", "medical attention", "receives treatment", "medical team",
                    "stretcher comes on"),
            "fr", List.of("joueur blesse", "soins medicaux", "intervention des soigneurs", "reste au sol",
                    "staff medical"),
            "it", List.of("giocatore infortunato", "soccorso medico", "cure mediche", "staff medico"),
            "de", List.of("verletzter spieler", "medizinische behandlung", "medizinisches personal",
                    "liegt verletzt am boden"));

    public CandidateDetectionQualitySettings {
        if (speechRateWindowMs < 2_000) {
            throw new IllegalArgumentException("speechRateWindowMs must be at least 2000");
        }
        if (!Double.isFinite(speechRateSpikeMultiplier) || speechRateSpikeMultiplier <= 1) {
            throw new IllegalArgumentException("speechRateSpikeMultiplier must be greater than one");
        }
        if (minimumWordsPerWindow < 1) {
            throw new IllegalArgumentException("minimumWordsPerWindow must be positive");
        }
        if (!Double.isFinite(pitchRiseRatio) || pitchRiseRatio <= 1) {
            throw new IllegalArgumentException("pitchRiseRatio must be greater than one");
        }
        if (!Double.isFinite(pitchVarianceRatio) || pitchVarianceRatio <= 0) {
            throw new IllegalArgumentException("pitchVarianceRatio must be positive");
        }
        requireUnitInterval(minimumVoicedFrameRatio, "minimumVoicedFrameRatio");
        requireUnitInterval(minimumLiveEventProbability, "minimumLiveEventProbability");
        requireUnitInterval(replayProbabilityThreshold, "replayProbabilityThreshold");
        if (replayTemporalWindowMs <= 0 || contextAttachWindowMs <= 0 || boundarySearchBackMs <= 0
                || attackBuildupMaximumLeadMs <= 0 || goalFallbackPreRollMs < 0
                || maximumCandidateDurationMs <= goalFallbackPreRollMs) {
            throw new IllegalArgumentException("candidate windows must be positive, goal pre-roll non-negative, "
                    + "and maximum duration greater than goal pre-roll");
        }
        requireUnitInterval(crowdZeroCrossingThreshold, "crowdZeroCrossingThreshold");
        requireUnitInterval(minimumGoalReactionConfidence, "minimumGoalReactionConfidence");
        requireUnitInterval(replayPenaltyWeight, "replayPenaltyWeight");
        requireUnitInterval(goalNegativeContextPenaltyWeight, "goalNegativeContextPenaltyWeight");
    }

    public static CandidateDetectionQualitySettings defaults() {
        return new CandidateDetectionQualitySettings(10_000, 1.8, 8, 1.25, 0.18, 0.34,
                0.55, 0.68, 120_000, 8_000, 90_000, 45_000, 30_000, 0.12,
                60_000, 0.45, 0.40, 0.45);
    }

    public List<String> replayPhrases(String language) {
        return phrasesFor(REPLAY_PHRASES, language);
    }

    public List<String> retrospectivePhrases(String language) {
        return phrasesFor(RETROSPECTIVE_PHRASES, language);
    }

    public List<String> buildupPhrases(String language) {
        return phrasesFor(BUILDUP_PHRASES, language);
    }

    public List<String> restartPhrases(String language) {
        return phrasesFor(RESTART_PHRASES, language);
    }

    public List<String> injuryPhrases(String language) {
        return phrasesFor(INJURY_PHRASES, language);
    }

    private static List<String> phrasesFor(Map<String, List<String>> phrases, String language) {
        String key = language.toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        return phrases.getOrDefault(key, List.of());
    }

    private static void requireUnitInterval(double value, String name) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be between zero and one");
        }
    }
}
