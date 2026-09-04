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
    val artifactStreamingChunkMs: Int? = null,
    val enableEndpoint: Boolean = true,
    val maxActivePaths: Int = 4,
    val hotwordsFile: String = "",
    val hotwordsScore: Float = 0.0f,
    val ruleFsts: String = "",
    val ruleFars: String = "",
    val blankPenalty: Float = 0.0f,
) {
    init {
        // The official legacy bilingual Zipformer deployment leaves both fields empty so
        // sherpa infers the transducer contract from the three model paths.
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

    val SMALL_BILINGUAL_ZH_EN = ModelProfile(
        id = "sherpa-small-bilingual-zh-en",
        version = "2023-02-16",
        artifact = ModelArtifact(
            directory = "small-bilingual-zh-en-2023-02-16",
            encoder = ModelFileSpec(
                name = "encoder-epoch-99-avg-1.int8.onnx",
                expectedSize = 42_980_793L,
                sha256 = "db6f51551762e40e549166fe041ea3e45464370b595e9ad23f06478ec3794fbb",
            ),
            decoder = ModelFileSpec(
                name = "decoder-epoch-99-avg-1.onnx",
                expectedSize = 13_877_276L,
                sha256 = "89be509a83175261695bdef5fd1c7b9ab1129a663d1284e7ba9f8507b21e0906",
            ),
            joiner = ModelFileSpec(
                name = "joiner-epoch-99-avg-1.int8.onnx",
                expectedSize = 3_228_485L,
                sha256 = "bdda356d6f9b8c2d7cee9ee0e26075fa537490f7fd06520be408d287073667b9",
            ),
            tokens = ModelFileSpec(
                name = "tokens.txt",
                expectedSize = 56_317L,
                sha256 = "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3",
            ),
        ),
        recognizer = ModelRecognizerProfile(
            // The official command leaves both values empty for this legacy model.
            modelType = "",
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
            // The root export's "32" is a model chunk length, not milliseconds.
            artifactStreamingChunkMs = null,
            enableEndpoint = true,
            maxActivePaths = 4,
            hotwordsScore = 1.5f,
        ),
        capabilities = ModelCapabilities(
            zh = true,
            en = true,
            streaming = true,
            hotwords = true,
            codeSwitch = true,
        ),
        evaluationLabel = "small-bilingual-zh-en",
    )

    val X_ASR_480 = ModelProfile(
        id = "x-asr-480",
        version = "689ff18c584d29910da37b6fe904db0c1489c9d1",
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
            enableEndpoint = false,
            maxActivePaths = 4,
            hotwordsScore = 1.5f,
        ),
        capabilities = ModelCapabilities(
            zh = true,
            en = true,
            streaming = true,
            hotwords = true,
            codeSwitch = true,
        ),
        evaluationLabel = "x-asr-zh-en-480ms",
    )

    val X_ASR_960 = ModelProfile(
        id = "x-asr-960",
        version = "689ff18c584d29910da37b6fe904db0c1489c9d1",
        artifact = ModelArtifact(
            directory = "x-asr-zh-en-960ms",
            encoder = ModelFileSpec(
                name = "encoder-960ms.onnx",
                expectedSize = 592_966_960L,
                sha256 = "dd9484b7c34c951495f3420f26f9f2ab706e748bc087cd14dfe0b90d3156264f",
            ),
            decoder = ModelFileSpec(
                name = "decoder-960ms.onnx",
                expectedSize = 11_309_084L,
                sha256 = "3658368d274a5d5fd39a7ac20c46bed0ad9cfea1f0feddef30d5d89797c1f499",
            ),
            joiner = ModelFileSpec(
                name = "joiner-960ms.onnx",
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
            artifactStreamingChunkMs = 960,
            enableEndpoint = false,
            maxActivePaths = 4,
            hotwordsScore = 1.5f,
        ),
        capabilities = ModelCapabilities(
            zh = true,
            en = true,
            streaming = true,
            hotwords = true,
            codeSwitch = true,
        ),
        evaluationLabel = "x-asr-zh-en-960ms",
    )
}
