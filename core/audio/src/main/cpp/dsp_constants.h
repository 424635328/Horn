#pragma once

// Frozen DSP constants for the stereo physical engine.
// Single source of truth: docs/spec/research/00-stereo-pipeline-contract.md section 3,
// with voice parameters from 01-horn-physical-modeling.md and scene parameters from
// 02-spatial-scene-acoustics.md. Every value here MUST be mirrored 1:1 by
// dsp/DspConstants.kt (C3/C6 rulings); do not change a value without a contract revision.

namespace dsp {

// --- Contract section 3: shared frozen constants -------------------------------------------
constexpr float MAKEUP_CAR = 0.47f;
constexpr float MAKEUP_TRAIN = 0.52f;
constexpr float MAKEUP_EBIKE = 0.18f;
constexpr float MAKEUP_MOTORCYCLE = 0.30f;
constexpr float MAKEUP_CUSTOM = 0.22f;
constexpr float MASTER_GAIN = 0.9f;
constexpr float PROTECTION_GAIN = 0.501f;
constexpr float HARD_CEILING = 0.98f;
constexpr float LIMITER_DRIVE = 1.0f;

constexpr float EXPR_PITCH_BEND = 0.08f;
constexpr float EXPR_DRIVE_RANGE = 0.35f;
constexpr float EXPR_NOISE_RANGE = 0.6f;
constexpr float EXPR_BRASS_BETA_BASE = 0.05f;
constexpr float EXPR_BRASS_BETA_RANGE = 0.30f;
constexpr float EXPR_BRASS_ASYM_BASE = 0.10f;
constexpr float EXPR_BRASS_ASYM_RANGE = 0.15f;
constexpr float EXPR_SMOOTHING = 0.0025f;  // ~tau 8.32 ms, kept as-is

// C4 ruling: sag is merged into the valve ODE; no separate BEND_START mechanism.
// kappa and tau follow the Kotlin DspConstants mirror (kappa within 01's [0.005, 0.015]).
constexpr float TRAIN_PITCH_SAG_ONSET = -0.018f;
constexpr float TRAIN_PITCH_SAG_MIN = -0.019f;
constexpr float TRAIN_PITCH_SAG_MAX = 0.015f;
constexpr float TRAIN_PITCH_SAG_KAPPA = 0.012f;
constexpr float TRAIN_PITCH_SAG_TAU_MS = 45.0f;

constexpr float VBS_WE = 0.70f;
constexpr float VBS_WO = 0.25f;
constexpr float VBS_GMIX = 0.45f;
constexpr float VBS_HP_FC = 90.0f;                 // 80..100 configurable band

constexpr float CORR_SILENCE_EPS = 1e-12f;

// --- Engine / buffer contract (section 2) ---------------------------------------------------
constexpr int SAMPLE_RATE = 48000;
constexpr int FRAMES_PER_CALLBACK = 128;
constexpr int MAX_CALLBACK_FRAMES = 512;
constexpr int WAVEFORM_SIZE = 256;
constexpr float DENORMAL_EPS = 1e-20f;             // block-level zero snap |x| < eps -> 0

// Parameter smoothing (section 2): y += a*(x-y), a = 1-exp(-1/(tau*fs)).
constexpr float TAU_PARAM_MS = 20.0f;
constexpr float TAU_EXPR_MS = 8.32f;
constexpr float TAU_BRASS_MS = 5.0f;
constexpr float TAU_MODE_MS = 30.0f;               // output-mode crossfade 20..50 ms
constexpr float TAU_SPACE_MS = 50.0f;              // scene crossfade 30..80 ms
constexpr float TAU_GAIN_MS = 20.0f;

// --- Physical exciter (01) ------------------------------------------------------------------
constexpr int WAVETABLE_SIZE = 2048;               // 2048+1 points stored
constexpr float BRASS_D0_CAR_MS = 0.8f;            // 38.4 samples @48 kHz
constexpr float BRASS_D0_TRAIN_MS = 0.8f;          // Kotlin AmplitudeDelaySteepener mirror
constexpr float BRASS_D0_CUSTOM_MS = 0.8f;
constexpr float BRASS_TAU_S_MS = 3.0f;             // envelope smoothing tau_s in [2,5] ms
constexpr float BRASS_BETA_MAX = 0.35f;
constexpr float BRASS_ASYM_MAX = 0.25f;
constexpr float BRASS_DELAY_MAX = 102.0f;          // ceil(d0_max*(1+beta_max)) + 4

// Fold-preserving pan convention (contract section 7 f): (g_L + g_R)/2 = 1.
constexpr float PAN_CENTER[2] = {1.0f, 1.0f};
constexpr float CAR_PAN_LOW[2] = {1.16f, 0.84f};
constexpr float CAR_PAN_HIGH[2] = {0.84f, 1.16f};

// Per-voice output stage constants (Kotlin synth mirrors: OUTPUT_GAIN / MAX_OUTPUT).
constexpr float VOICE_MAX_OUTPUT = 0.95f;
constexpr float CAR_OUTPUT_GAIN = 0.82f;
constexpr float TRAIN_OUTPUT_GAIN = 0.88f;
constexpr float EBIKE_OUTPUT_GAIN = 0.95f;
constexpr float MOTO_OUTPUT_GAIN = 0.82f;
constexpr float CUSTOM_OUTPUT_GAIN = 1.0f;

// CAR dual snail horn (01 section 2.4). Micro-detune is an AM slow beat (section 7 h):
// exact 400/500 Hz spectral lines, 0.4 Hz beat at depth 0.007 (total sway 0.025).
constexpr float CAR_F_LOW = 400.0f;
constexpr float CAR_F_HIGH = 500.0f;
constexpr float CAR_WEIGHT_LOW = 0.52f;
constexpr float CAR_WEIGHT_HIGH = 0.48f;
constexpr float CAR_HARMONIC_NORM = 0.43f;
constexpr float CAR_LOW_PASS_HZ = 6000.0f;
constexpr float CAR_THROAT_HP_LOW = 260.0f;
constexpr float CAR_THROAT_HP_HIGH = 330.0f;
constexpr float CAR_NOISE_LEVEL = 0.0055f;
constexpr float CAR_NOISE_SMOOTH = 0.35f;
constexpr float CAR_SWAY_HZ = 5.5f;
constexpr float CAR_SWAY_DEPTH = 0.018f;
constexpr float CAR_BEAT_HZ = 0.4f;
constexpr float CAR_BEAT_DEPTH = 0.007f;
constexpr float CAR_SHAPE_K = 1.55f;
constexpr float CAR_RELAY_AMP = 0.05f;
constexpr float CAR_RELAY_TAU_S = 0.003f;
constexpr float CAR_RELAY_BURST2 = 48.0f;          // second shock 1 ms after noteOn
constexpr float CAR_RELAY_BPF_HZ = 1500.0f;
constexpr float CAR_RELAY_BPF_Q = 1.2f;
constexpr float CAR_RING_F = 440.0f;
constexpr float CAR_RING_ZETA = 0.02f;
constexpr float CAR_RING_TAU_OFF_S = 0.045f;
constexpr float CAR_RING_EXCITE = 0.35f;

// Deterministic noise seeds (Kotlin NoiseSource mirrors).
constexpr uint32_t NOISE_SEED_CAR = 0x4D595DF4u;
constexpr uint32_t NOISE_SEED_TRAIN = 0x51A1C0DEu;
constexpr uint32_t NOISE_SEED_CUSTOM = 0x1CEDC0DEu;

// Frozen oscillator initial phases (Kotlin WavetableOscillator mirrors).
constexpr float PHASE_CUSTOM[2] = {0.09f, 0.43f};
constexpr float PHASE_EBIKE[3] = {0.07f, 0.53f, 0.21f};
constexpr float PHASE_MOTO = 0.13f;
constexpr float PHASE_TRAIN_PAIR[2] = {0.03f, 0.41f};

// Formant group (01 section 2.2.3)
constexpr float FORMANT_F[3] = {700.0f, 1400.0f, 2800.0f};
constexpr float FORMANT_Q[3] = {2.0f, 2.5f, 3.0f};
constexpr float FORMANT_DB[3] = {0.0f, 2.0f, 4.0f};

// TRAIN chord (01 section 2.5): voices V1..V5 = B2 / Eb3 / F#4 / Eb4 / B4
constexpr int TRAIN_VOICES = 5;
constexpr float TRAIN_F[TRAIN_VOICES] = {123.47f, 155.56f, 369.99f, 311.13f, 493.88f};
constexpr float TRAIN_GAIN_A[TRAIN_VOICES] = {0.06f, 0.10f, 0.16f, 0.18f, 0.14f};
// B2/Eb3 are single oscillators (section 7 d); chord voices keep +/-0.4% pairs.
constexpr float TRAIN_GAIN_B[TRAIN_VOICES] = {0.0f, 0.0f, 0.13f, 0.14f, 0.11f};
constexpr float TRAIN_DETUNE = 0.004f;             // pair x0.996 / x1.004
// Fold-preserving pans (section 7 f): (g_L+g_R)/2 = 1 per note.
constexpr float TRAIN_PAN_L[TRAIN_VOICES] = {1.0f, 1.3f, 0.8f, 1.2f, 0.7f};
constexpr float TRAIN_PAN_R[TRAIN_VOICES] = {1.0f, 0.7f, 1.2f, 0.8f, 1.3f};
constexpr float TRAIN_T_ON_MS[TRAIN_VOICES] = {0.0f, 12.0f, 24.0f, 36.0f, 45.0f};
constexpr float TRAIN_T_OFF_MS[TRAIN_VOICES] = {0.0f, 11.0f, 22.0f, 33.0f, 45.0f};
constexpr float TRAIN_TAU_OPEN_MS[TRAIN_VOICES] = {15.0f, 18.0f, 22.0f, 25.0f, 28.0f};
constexpr float TRAIN_TAU_CLOSE_MS[TRAIN_VOICES] = {30.0f, 32.0f, 35.0f, 38.0f, 40.0f};
constexpr float TRAIN_AIR_LEVEL = 0.055f;
constexpr float TRAIN_AIR_BPF1_HZ = 1200.0f;
constexpr float TRAIN_AIR_BPF1_Q = 0.8f;
constexpr float TRAIN_AIR_BPF2_HZ = 2400.0f;
constexpr float TRAIN_AIR_BPF2_Q = 1.0f;
constexpr float TRAIN_SHAPE_K = 1.6f;              // section 7 g: Kotlin v3 DRIVE

// EBIKE (01 section 2.6)
constexpr float EBIKE_F_A = 2740.0f;
constexpr float EBIKE_F_B = 2785.0f;
constexpr float EBIKE_F_BODY = 685.0f;
constexpr float EBIKE_GAIN_A = 0.50f;
constexpr float EBIKE_GAIN_B = 0.32f;
constexpr float EBIKE_GAIN_BODY = 0.13f;
constexpr float EBIKE_LP_HZ = 4200.0f;             // C7: canonical 4.2 kHz
constexpr float EBIKE_LP_Q = 0.707f;
constexpr float EBIKE_NOTCH_HZ = 5500.0f;
constexpr float EBIKE_NOTCH_Q = 3.0f;
constexpr float EBIKE_NOTCH_DB = -6.0f;
constexpr float EBIKE_HP_HZ = 500.0f;
constexpr float EBIKE_CLICK_AMP = 0.03f;           // section 7 g: Kotlin DC_CLICK_AMPLITUDE
constexpr float EBIKE_CLICK_MS = 1.0f;
constexpr float EBIKE_CLICK_HP_HZ = 2000.0f;
constexpr float EBIKE_SHAPE_K = 1.85f;
constexpr float EBIKE_SHAPE_ASYM = 0.08f;

// MOTORCYCLE (01 section 2.7)
constexpr float MOTO_F = 444.0f;
constexpr float MOTO_FLUTTER_HZ = 29.0f;
constexpr float MOTO_AM_DEPTH = 0.18f;
constexpr float MOTO_FM_DEPTH = 0.003f;
constexpr float MOTO_FORMANT_F[2] = {680.0f, 1050.0f};
constexpr float MOTO_FORMANT_Q[2] = {1.5f, 2.0f};
constexpr float MOTO_FORMANT_DB[2] = {4.0f, 3.0f};
constexpr float MOTO_HP_HZ = 190.0f;
constexpr float MOTO_LP_HZ = 4800.0f;
constexpr float MOTO_SHAPE_K = 2.0f;
constexpr float MOTO_SHAPE_ASYM = 0.12f;

// CUSTOM mapping (01 section 2.8). Harmonics follow Kotlin CustomHornSynth (section 7 c):
// fixed 12 terms, N_max band-limited truncation, w = h^-(1.45-0.30r) * (0.22+0.78r), first = 1.
constexpr float CUSTOM_F_MIN = 150.0f;
constexpr float CUSTOM_F_MAX = 3000.0f;
constexpr float CUSTOM_FC_SCALE = 0.55f;
constexpr float CUSTOM_FC_MIN = 80.0f;
constexpr float CUSTOM_FC_MAX = 1500.0f;
constexpr float CUSTOM_DECAY_MAX = 1.45f;
constexpr float CUSTOM_DECAY_RANGE = 0.30f;
constexpr float CUSTOM_TILT_BASE = 0.22f;
constexpr float CUSTOM_TILT_RANGE = 0.78f;
constexpr int CUSTOM_HARMONIC_COUNT = 12;
constexpr float CUSTOM_LP_BASE = 3800.0f;
constexpr float CUSTOM_LP_RANGE = 6000.0f;
constexpr float CUSTOM_MOUTH_HP_MAX = 120.0f;
constexpr float CUSTOM_MOUTH_HP_MIN = 80.0f;
constexpr float CUSTOM_SHAPE_K_BASE = 1.2f;
constexpr float CUSTOM_SHAPE_K_RANGE = 5.2f;
constexpr float CUSTOM_PRIMARY_WEIGHT = 0.72f;
constexpr float CUSTOM_SECONDARY_WEIGHT = 0.42f;
constexpr float CUSTOM_RING_F = 440.0f;
constexpr float CUSTOM_RING_EXCITE = 0.2f;

// Expression pan / panning convention: fold-preserving amplitude pans, direct path pure
// amplitude (contract 02 section 5 C1: no ITD on direct sound; section 7 f: (g_L+g_R)/2 = 1).

// Harmonic gain tables (mirrored from Kotlin dsp/HarmonicWavetable users).
constexpr float CAR_LOW_HARMONICS[8] = {1.0f, 0.62f, 0.44f, 0.30f, 0.20f, 0.14f, 0.10f, 0.07f};
constexpr float CAR_HIGH_HARMONICS[7] = {1.0f, 0.55f, 0.42f, 0.28f, 0.18f, 0.12f, 0.08f};
constexpr float TRAIN_CHORD_HARMONICS[8] = {1.0f, 0.58f, 0.37f, 0.24f, 0.15f, 0.10f, 0.065f, 0.04f};
constexpr float TRAIN_SUB_HARMONICS[4] = {1.0f, 0.32f, 0.16f, 0.08f};
constexpr float EBIKE_PULSE_HARMONICS[8] = {1.0f, 0.42f, 0.46f, 0.22f, 0.19f, 0.10f, 0.08f, 0.05f};
constexpr float EBIKE_BODY_HARMONICS[3] = {1.0f, 0.28f, 0.12f};
constexpr float MOTO_DIAPHRAGM_HARMONICS[8] = {1.0f, 0.50f, 0.36f, 0.25f, 0.17f, 0.11f, 0.07f, 0.045f};

// --- Scene table (02 section 4) -------------------------------------------------------------
// FDN delay tables (integer samples, frozen literals, 02 section 6.5).
constexpr int FDN_TABLE_A[8] = {1901, 1973, 2039, 2099, 2161, 2221, 2281, 2341};  // M=8 tunnel
constexpr int FDN_TABLE_B[8] = {1103, 1181, 1259, 1361, 1433, 1511, 1583, 1657};  // M=8 garage
constexpr int FDN_TABLE_C[4] = {1301, 1433, 1567, 1697};                          // M=4 street/open
constexpr int FDN_MAX_DELAY = 2341;

struct SceneSpec {
    float wetMix;
    float preDelayMs;
    int tailMs;
    float widthW;        // FDN output M/S width
    float fdnG;          // feedback gain, rho(A*G) <= g < 1
    float fdnDamping;    // one-pole damping d in [0, 0.95]
    int fdnTable;        // 0 = C (M=4), 1 = A (M=8), 2 = B (M=8)
    int fdnLines;        // 4 or 8
    int ismOrder;        // 6N image sources; 0 = sparse far echoes instead
    float wallLpHz;      // reflection high-frequency absorption corner
    float groundDelaySamples;
    float groundRho;
    float groundLpHz;
    float flutterSamples;  // 0 disables the parallel-plate colouring comb
    float flutterRho;
    float airDistanceM;  // representative distance of the 02 section 4 band
};

// Comb delays follow 02 section 6.5 round-half-up (section 7 a, locked tests):
// STREET/OPEN ground 210 = 2*0.75/343*48000 = 209.9 -> 210; GARAGE slab 672 = 671.7 -> 672;
// TUNNEL flutter 2237 comes from the frozen 46.6 ms period (46.6 ms x 48 = 2236.8 -> 2237).
constexpr SceneSpec SCENE_STREET = {
    0.10f, 8.0f, 60, 0.5f, 0.567f, 0.204f, 0, 4, 2, 3200.0f,
    210.0f, 0.40f, 3200.0f, 0.0f, 0.0f, 35.0f};
constexpr SceneSpec SCENE_TUNNEL = {
    0.38f, 18.0f, 1200, 0.7f, 0.914f, 0.262f, 1, 8, 2, 5000.0f,
    0.0f, 0.0f, 0.0f, 2237.0f, 0.30f, 70.0f};
constexpr SceneSpec SCENE_OPEN_HIGHWAY = {
    0.22f, 34.0f, 400, 0.4f, 0.375f, 0.225f, 0, 4, 0, 2500.0f,
    210.0f, 0.35f, 3000.0f, 0.0f, 0.0f, 150.0f};
constexpr SceneSpec SCENE_GARAGE = {
    0.30f, 12.0f, 800, 0.6f, 0.853f, 0.141f, 2, 8, 2, 6000.0f,
    672.0f, 0.50f, 6000.0f, 0.0f, 0.0f, 25.0f};

constexpr int SCENE_COUNT = 4;
constexpr SceneSpec SCENES[SCENE_COUNT] = {
    SCENE_STREET, SCENE_TUNNEL, SCENE_OPEN_HIGHWAY, SCENE_GARAGE};

// OPEN_HIGHWAY sparse far echoes (96/184 ms, -21/-28 dB, crossed 1.16/0.84 ILD split),
// implemented as ordinary taps on the shared dry history ring.
constexpr float FAR_ECHO_DELAY[2] = {4608.0f, 8832.0f};
constexpr float FAR_ECHO_GAIN_L[2] = {0.103385f, 0.033441f};
constexpr float FAR_ECHO_GAIN_R[2] = {0.074865f, 0.046181f};

// ISM buffers: history covers the longest sparse echo (184 ms) plus margin (02 section 6.1).
constexpr int ISM_HISTORY = 11520;
constexpr int ISM_MAX_TAPS = 14;
constexpr float ISM_HEAD_RADIUS = 0.0875f;   // Woodworth sphere head
constexpr float ISM_SOUND_SPEED = 343.0f;
constexpr float ISM_ITD_MAX_MS = 0.66f;      // C2 budget: <= 0.66 ms channel delay
constexpr float ISM_TAP_GAIN_MAX = 0.316f;   // -10 dB relative to direct
constexpr float ISM_SUM_GAIN_MAX = 0.333f;   // coherent reflection sum budget

// Ground / flutter comb physical limits (section 7 g: 2304, SceneSpecCatalog.COMB_SAMPLES).
constexpr float COMB_RHO_MAX = 0.95f;
constexpr int COMB_MAX_DELAY = 2304;

// --- Output mode (03) ------------------------------------------------------------------------
constexpr int MODE_SPEAKER_VBS = 0;
constexpr int MODE_HEADPHONE_FULL_RANGE = 1;

// VBS chain (03 section 2.1)
constexpr float VBS_BPF_LO_HZ = 80.0f;
constexpr float VBS_BPF_HI_HZ = 280.0f;
constexpr float VBS_FIR_LO_HZ = 250.0f;
constexpr float VBS_FIR_HI_HZ = 900.0f;
constexpr int VBS_FIR_TAPS = 63;
constexpr int VBS_ALIGN_DELAY = 31;           // (N-1)/2 group delay in samples
constexpr float VBS_NLD_MEAN_HZ = 8.0f;
constexpr float VBS_NLD_INPUT_LIMIT = 0.95f;
constexpr float VBS_STEREO_SPREAD = 0.15f;    // e in [0.1, 0.2]
constexpr float VBS_INJECT_CUSTOM_FC = 280.0f;

// Headphone width chain (03 section 1)
constexpr float HP_WIDTH = 0.9f;              // w in [0.8, 1.0]
constexpr float HP_ILD_DB = 2.0f;             // +1..+3 dB high shelf on S @2 kHz
constexpr float HP_ILD_FC = 2000.0f;
constexpr float HP_ITD_SAMPLES = 28.0f;       // <= 28 samples (0.6 ms) on S only

// StereoOutputStage (03 section 2.4)
constexpr float LIMITER_THRESHOLD = 0.98f;
constexpr float LIMITER_TAU_ATT_MS = 1.0f;    // attack 1..2 ms band
constexpr float LIMITER_TAU_REL_MS = 80.0f;   // release 50..120 ms band
constexpr int LIMITER_LOOKAHEAD = 96;         // 2 ms, within 48..96 sample band
constexpr float TANH_DRIVE = 1.0f;

// True-peak 4x polyphase FIR (03 section 2.4.1, self-designed windowed sinc table).
constexpr int TP_PHASES = 4;
constexpr int TP_TAPS = 12;
constexpr float TP_COEFFS[TP_PHASES][TP_TAPS] = {
    {-0.000001f, 0.000173f, -0.001877f, 0.009869f, -0.035760f, 0.122550f,
     0.971976f, -0.087638f, 0.026472f, -0.006817f, 0.001130f, -0.000077f},
    {-0.000017f, 0.000840f, -0.007212f, 0.033709f, -0.115904f, 0.440968f,
     0.766135f, -0.155723f, 0.046777f, -0.011066f, 0.001562f, -0.000068f},
    {-0.000068f, 0.001562f, -0.011066f, 0.046777f, -0.155723f, 0.766135f,
     0.440968f, -0.115904f, 0.033709f, -0.007212f, 0.000840f, -0.000017f},
    {-0.000077f, 0.001130f, -0.006817f, 0.026472f, -0.087638f, 0.971976f,
     0.122550f, -0.035760f, 0.009869f, -0.001877f, 0.000173f, -0.000001f}};

// --- Loudness feed-forward (03 section 4) -----------------------------------------------------
// G_total = makeup[v] * g_scene[s] * g_mode[v][m] * (protection ? 0.501 : 1) * 0.9
// g_scene*g_mode given as dB: sceneColumn[s] + modeOffset[v] (speaker mode only).
// GARAGE column: 03 section 4 predates the fourth scene; frozen as the midpoint of the
// TUNNEL/OPEN_ROAD columns (-0.65), identical to dsp/LoudnessTable.kt's derivation.
constexpr float LOUD_SCENE_DB[SCENE_COUNT] = {-0.2f, -0.8f, -0.5f, -0.65f};
constexpr float LOUD_MODE_OFFSET_DB[5] = {-0.3f, -1.0f, -0.3f, -0.3f, -0.7f};
// voice order: CAR / TRAIN / EBIKE / MOTORCYCLE / CUSTOM

// --- Air absorption (02 section 3.3, ISO 9613-1:1993) -----------------------------------------
// Shelf pair (G1 @ 2 kHz, G2 @ 8 kHz) is fitted at construction from the ISO 9613-1
// Eq.(3)-(5) attenuation over each scene's representative distance (20 C / 50 % RH),
// with the same bisection fit as dsp/SceneSpecCatalog.kt, so both ends converge on
// identical gains. No hand-copied approximation enters the runtime path.
constexpr float AIR_SHELF_F1 = 2000.0f;
constexpr float AIR_SHELF_F2 = 8000.0f;

// --- Telemetry slot layout for nativeReadTelemetry (contract 00 section 5) ---------------------
// [0] rms = max(rmsL, rmsR)   [1] latencyMs   [2] xrun   [3] renderMicros   [4] isPlaying
// [5] rmsL   [6] rmsR   [7] stereoCorrelation   [8] outputMode ordinal
constexpr int TELEMETRY_SLOTS = 9;

}  // namespace dsp
