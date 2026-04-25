import os
os.environ['TF_CPP_MIN_LOG_LEVEL'] = '3'
os.environ['KMP_DUPLICATE_LIB_OK'] = 'True'
print("Attempting to import tensorflow...")
import tensorflow as tf
print("Success! TensorFlow version:", tf.__version__)
print("Devices detected:", tf.config.list_physical_devices())
