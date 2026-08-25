"""Generates the fixture live model PipelineResilienceTest stages before
phaseA runs.

Not run automatically as part of the resilience-test harness or its build -
producing a valid TFLite flatbuffer needs the TensorFlow toolchain, which
this Android repo otherwise has no reason to depend on. Run this once,
locally, whenever the fixture needs (re)generating:

    pip install tensorflow
    python scripts/generate_dummy_policy_model.py

Output: app/src/androidTest/assets/fixtures/dummy_policy_model.tflite
(run from the repo root; the script creates the fixtures/ directory if
needed).

I/O contract (must match org.example.syncora.ml.PolicyInferenceEngine's
validateShapes()):
  - one float32 input tensor that flattens to STATE_DIMENSION elements
    (org.example.syncora.bitget.MdpStateSnapshot.STATE_DIMENSION - keep
    this constant in sync with that file if it ever changes)
  - one float32 output tensor that reduces to a single scalar

The weights below are small fixed (non-random) constants, not trained on
anything - this fixture exists only to give the resilience harness a live
model to run inference/log/train against end-to-end; its predictions carry
no signal and this file must never be used for anything but this test.
"""

import os

import tensorflow as tf

# Keep in sync with org.example.syncora.bitget.MdpStateSnapshot.STATE_DIMENSION.
STATE_DIMENSION = 11

OUTPUT_PATH = "app/src/androidTest/assets/fixtures/dummy_policy_model.tflite"


def build_model() -> tf.keras.Model:
    inputs = tf.keras.Input(shape=(STATE_DIMENSION,), dtype=tf.float32, name="state")
    hidden = tf.keras.layers.Dense(
        8,
        activation="relu",
        kernel_initializer=tf.keras.initializers.Constant(0.01),
        bias_initializer="zeros",
        name="hidden",
    )(inputs)
    # tanh keeps the raw output in [-1, 1], matching what
    # PolicyInferenceEngine's kdoc assumes the model's own output layer
    # does - PolicyInferenceEngine.infer() re-clamps defensively regardless.
    action = tf.keras.layers.Dense(
        1,
        activation="tanh",
        kernel_initializer=tf.keras.initializers.Constant(0.01),
        bias_initializer="zeros",
        name="action",
    )(hidden)
    return tf.keras.Model(inputs=inputs, outputs=action, name="dummy_policy")


def main() -> None:
    model = build_model()
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_bytes = converter.convert()

    os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)
    with open(OUTPUT_PATH, "wb") as f:
        f.write(tflite_bytes)

    print(f"Wrote {len(tflite_bytes)} bytes to {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
