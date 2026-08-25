# dummy_policy_model.tflite goes here

This directory intentionally has no committed `.tflite` binary yet.
Generate it once with:

```
pip install tensorflow
python scripts/generate_dummy_policy_model.py
```

`PipelineResilienceTest.seedFixtureLiveModel()` loads
`fixtures/dummy_policy_model.tflite` from this androidTest assets
directory before phaseA runs. See that script's header and
`package-info.kt` gap #4 for details.
