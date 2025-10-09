package com.research.llmbattery.models

/**
 * Data class representing the configuration for an LLM model.
 * Contains information about the model's name, path, quantization type, and size.
 */
data class ModelConfig(
    val modelName: String,
    val modelPath: String,
    val quantization: String,
    val sizeInMB: Float
) {
    companion object {
        /**
         * Factory method for creating a ModelConfig instance for testing purposes.
         * @param modelName The name of the model
         * @param modelPath The file path to the model
         * @param quantization The quantization type (e.g., "2-bit", "3-bit", "4-bit")
         * @param sizeInMB The size of the model in megabytes
         * @return A new ModelConfig instance
         */
        fun create(
            modelName: String,
            modelPath: String,
            quantization: String,
            sizeInMB: Float
        ): ModelConfig {
            return ModelConfig(
                modelName = modelName,
                modelPath = modelPath,
                quantization = quantization,
                sizeInMB = sizeInMB
            )
        }
        
        /**
         * Creates a sample ModelConfig for testing with default values.
         * @return A sample ModelConfig instance
         */
        fun createSample(): ModelConfig {
            return ModelConfig(
                modelName = "sample-model",
                modelPath = "/data/models/sample.gguf",
                quantization = "4-bit",
                sizeInMB = 100.0f
            )
        }
    }
}
