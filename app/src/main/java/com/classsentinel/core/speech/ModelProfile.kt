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

/** Artifact layout and integrity metadata for the production model release. */
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

/** Runtime recognizer settings owned by the pinned production model profile. */
internal data class ModelRecognizerProfile(
    val modelType: String,
    val modelingUnit: String,
    val decodingMethod: String,
    val provider: String = "cpu",
    val sampleRate: Int,
    val featureDim: Int,
    val endpoint: ModelEndpointProfile,
    val artifactStreamingChunkMs: Int? = null,
    /** Endpoint policy for the live classroom session. */
    val enableEndpoint: Boolean = true,
    /** Endpoint setting from the upstream official deployment/smoke command. */
    val officialDeploymentEnableEndpoint: Boolean = true,
    val maxActivePaths: Int = 4,
    val hotwordsFile: String = "",
    val hotwordsScore: Float = 0.0f,
    val ruleFsts: String = "",
    val ruleFars: String = "",
    val blankPenalty: Float = 0.0f,
) {
    init {
        require(decodingMethod.isNotBlank()) { "MODEL_DECODING_METHOD_INVALID" }
        require(provider.isNotBlank()) { "MODEL_PROVIDER_INVALID" }
        require(sampleRate > 0) { "MODEL_SAMPLE_RATE_INVALID" }
        require(featureDim > 0) { "MODEL_FEATURE_DIM_INVALID" }
        require(artifactStreamingChunkMs == null || artifactStreamingChunkMs > 0) { "MODEL_CHUNK_INVALID" }
        require(maxActivePaths > 0) { "MODEL_MAX_ACTIVE_PATHS_INVALID" }
        require(hotwordsScore.isFinite()) { "MODEL_HOTWORDS_SCORE_INVALID" }
        require(blankPenalty.isFinite()) { "MODEL_BLANK_PENALTY_INVALID" }
    }
}

/** Distribution is explicit even though production has one bundled owner. */
internal sealed interface ModelDistribution {
    data object Bundled : ModelDistribution
}

/** Single source of truth for the only installable production ASR model. */
internal data class ModelProfile(
    val id: String,
    val version: String,
    val artifact: ModelArtifact,
    val recognizer: ModelRecognizerProfile,
    val displayName: String,
    val distribution: ModelDistribution = ModelDistribution.Bundled,
) {
    init {
        require(id.matches(ID_PATTERN)) { "MODEL_PROFILE_ID_INVALID" }
        require(version.isNotBlank()) { "MODEL_PROFILE_VERSION_INVALID" }
        require(displayName.isNotBlank()) { "MODEL_PROFILE_LABEL_INVALID" }
        require(distribution is ModelDistribution.Bundled) { "MODEL_PROFILE_DISTRIBUTION_INVALID" }
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]*")
    }
}

/** Checked-in production profile; the runtime must never resolve another ASR profile. */
internal object ModelProfiles {
    private const val X_ASR_REVISION = "689ff18c584d29910da37b6fe904db0c1489c9d1"

    val X_ASR_480 = ModelProfile(
        id = "x-asr-480",
        version = X_ASR_REVISION,
        artifact = ModelArtifact(
            directory = "x-asr-zh-en-480ms",
            encoder = ModelFileSpec(
                name = "encoder-480ms.onnx",
                expectedSize = 592_968_361L,
                sha256 = "0c3454033d249081df124ddcd7adaf3deca07d0b999b26f2ee5d2475d37abc74",
            ),
            decoder = ModelFileSpec(
                name = "decoder-480ms.onnx",
                expectedSize = 11_309_084L,
                sha256 = "3658368d274a5d5fd39a7ac20c46bed0ad9cfea1f0feddef30d5d89797c1f499",
            ),
            joiner = ModelFileSpec(
                name = "joiner-480ms.onnx",
                expectedSize = 10_260_467L,
                sha256 = "03781c98165a2385024c9cecdd2b6b13310d81db23a62c7da420782c2915cf81",
            ),
            tokens = ModelFileSpec(
                name = "tokens.txt",
                expectedSize = 58_806L,
                sha256 = "b818a60878b9aae978cbb8ad594acbd403d76d1af2e31ef4197c84e2dbdba27c",
            ),
        ),
        recognizer = ModelRecognizerProfile(
            modelType = "zipformer2",
            // The official deployment wrapper omits modeling_unit; keep the runtime default.
            modelingUnit = "",
            decodingMethod = "greedy_search",
            provider = "cpu",
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
                    minTrailingSilence = 1.2f,
                    minUtteranceLength = 0.0f,
                ),
                rule3 = ModelEndpointRule(
                    mustContainNonSilence = false,
                    minTrailingSilence = 0.0f,
                    minUtteranceLength = 20.0f,
                ),
            ),
            artifactStreamingChunkMs = 480,
            enableEndpoint = true,
            officialDeploymentEnableEndpoint = false,
            maxActivePaths = 4,
            hotwordsScore = 1.5f,
        ),
        displayName = "X-ASR 中英增强模型",
        distribution = ModelDistribution.Bundled,
    )

    /** The only production ASR profile used by classroom runtime and settings facts. */
    val PRODUCTION: ModelProfile = X_ASR_480
}
