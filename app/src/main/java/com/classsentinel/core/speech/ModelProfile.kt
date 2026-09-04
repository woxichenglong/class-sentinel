package com.classsentinel.core.speech

/** One checked-in model file required by a [ModelProfile]. */
internal data class ModelFileSpec(
    val name: String,
    val expectedSize: Long,
    val sha256: String,
) {
    init {
        require(name.isNotBlank() && '/' !in name && '\\' !in name) { "MODEL_FILE_NAME_INVALID" }
        require(expectedSize > 0L) { "MODEL_FILE_SIZE_INVALID" }
        require(sha256.matches(SHA256_PATTERN)) { "MODEL_FILE_SHA256_INVALID" }
    }

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

/** Artifact layout and integrity metadata for one model release. */
internal data class ModelArtifact(
    val directory: String,
    val encoder: ModelFileSpec,
    val decoder: ModelFileSpec,
    val joiner: ModelFileSpec,
    val tokens: ModelFileSpec,
) {
    init {
        require(directory.isNotBlank() && '/' !in directory && '\\' !in directory) {
            "MODEL_DIRECTORY_INVALID"
        }
        require(files.map { it.name }.toSet().size == files.size) { "MODEL_FILE_NAMES_DUPLICATE" }
    }

    val files: List<ModelFileSpec>
        get() = listOf(encoder, decoder, joiner, tokens)
}

/** One endpoint rule expressed without a dependency on the sherpa Android API. */
internal data class ModelEndpointRule(
    val mustContainNonSilence: Boolean,
    val minTrailingSilence: Float,
    val minUtteranceLength: Float,
) {
    init {
        require(minTrailingSilence.isFinite() && minTrailingSilence >= 0.0f) {
            "MODEL_ENDPOINT_SILENCE_INVALID"
        }
        require(minUtteranceLength.isFinite() && minUtteranceLength >= 0.0f) {
            "MODEL_ENDPOINT_UTTERANCE_INVALID"
        }
    }
}

/** Endpoint policy owned by the model profile rather than the recognizer factory. */
internal data class ModelEndpointProfile(
    val rule1: ModelEndpointRule,
    val rule2: ModelEndpointRule,
    val rule3: ModelEndpointRule,
)

/** Runtime recognizer settings that may differ between model/chunk variants. */
internal data class ModelRecognizerProfile(
    val modelType: String,
    val modelingUnit: String,
    val decodingMethod: String,
    val provider: String = "cpu",
    val sampleRate: Int,
    val featureDim: Int,
    val endpoint: ModelEndpointProfile,
    val streamChunkMs: Int? = null,
    val enableEndpoint: Boolean = true,
    val maxActivePaths: Int = 4,
    val hotwordsFile: String = "",
    val hotwordsScore: Float = 0.0f,
    val ruleFsts: String = "",
    val ruleFars: String = "",
    val blankPenalty: Float = 0.0f,
) {
    init {
        require(modelType.isNotBlank()) { "MODEL_TYPE_INVALID" }
        require(modelingUnit.isNotBlank()) { "MODEL_MODELING_UNIT_INVALID" }
        require(decodingMethod.isNotBlank()) { "MODEL_DECODING_METHOD_INVALID" }
        require(provider.isNotBlank()) { "MODEL_PROVIDER_INVALID" }
        require(sampleRate > 0) { "MODEL_SAMPLE_RATE_INVALID" }
        require(featureDim > 0) { "MODEL_FEATURE_DIM_INVALID" }
        require(streamChunkMs == null || streamChunkMs > 0) { "MODEL_CHUNK_INVALID" }
        require(maxActivePaths > 0) { "MODEL_MAX_ACTIVE_PATHS_INVALID" }
        require(hotwordsScore.isFinite()) { "MODEL_HOTWORDS_SCORE_INVALID" }
        require(blankPenalty.isFinite()) { "MODEL_BLANK_PENALTY_INVALID" }
    }
}

/** Capabilities used by evaluation and UI selection; no runtime behavior is implied. */
internal data class ModelCapabilities(
    val zh: Boolean,
    val en: Boolean,
    val streaming: Boolean,
    val hotwords: Boolean,
    val codeSwitch: Boolean,
)

/** Single source of truth for one installable/evaluable ASR model variant. */
internal data class ModelProfile(
    val id: String,
    val version: String,
    val artifact: ModelArtifact,
    val recognizer: ModelRecognizerProfile,
    val capabilities: ModelCapabilities,
    val evaluationLabel: String,
) {
    init {
        require(id.matches(ID_PATTERN)) { "MODEL_PROFILE_ID_INVALID" }
        require(version.isNotBlank()) { "MODEL_PROFILE_VERSION_INVALID" }
        require(evaluationLabel.isNotBlank()) { "MODEL_PROFILE_LABEL_INVALID" }
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]*")
    }
}

/** Checked-in profiles. New model variants must add a profile instead of factory branches. */
internal object ModelProfiles {
    val ZIPFORMER_ZH_14M = ModelProfile(
        id = "sherpa-zh-14m",
        version = "2023-02-23",
        artifact = ModelArtifact(
            directory = "zipformer-zh-14M-2023-02-23",
            encoder = ModelFileSpec(
                name = "encoder-epoch-99-avg-1.int8.onnx",
                expectedSize = 21_621_684L,
                sha256 = "1c556ea57cec304e55ec4b72e52c1cc098bb01476ed7d90f3de939fe126487b1",
            ),
            decoder = ModelFileSpec(
                name = "decoder-epoch-99-avg-1.onnx",
                expectedSize = 7_509_745L,
                sha256 = "5ee0f03a2768ff1d5c83ef3a493243c7935d316cd41280037b14783a3467cc78",
            ),
            joiner = ModelFileSpec(
                name = "joiner-epoch-99-avg-1.int8.onnx",
                expectedSize = 1_795_562L,
                sha256 = "a7cf9d82757bdcf786059454495a9ca95e4bd7347f72473fc08d794475c36169",
            ),
            tokens = ModelFileSpec(
                name = "tokens.txt",
                expectedSize = 48_697L,
                sha256 = "8b294db9045d6e5f94647f4c1eec1af4da143a75053c399611444b378ff966ac",
            ),
        ),
        recognizer = ModelRecognizerProfile(
            modelType = "zipformer",
            modelingUnit = "cjkchar",
            decodingMethod = "greedy_search",
            sampleRate = 16_000,
            featureDim = 80,
            endpoint = ModelEndpointProfile(
                rule1 = ModelEndpointRule(
                    mustContainNonSilence = false,
                    minTrailingSilence = 2.4f,
                    minUtteranceLength = 0.0f,
                ),
                rule2 = ModelEndpointRule(
                    mustContainNonSilence = true,
                    minTrailingSilence = 1.4f,
                    minUtteranceLength = 0.0f,
                ),
                rule3 = ModelEndpointRule(
                    mustContainNonSilence = false,
                    minTrailingSilence = 0.0f,
                    minUtteranceLength = 20.0f,
                ),
            ),
        ),
        capabilities = ModelCapabilities(
            zh = true,
            en = false,
            streaming = true,
            hotwords = false,
            codeSwitch = false,
        ),
        evaluationLabel = "baseline-zh-14m",
    )
}
