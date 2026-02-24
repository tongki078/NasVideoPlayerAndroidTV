import urllib.request
import time

try:
    print("Requesting refresh_cleaned_names...")
    response = urllib.request.urlopen("http://localhost:5000/refresh_cleaned_names", timeout=60)
    print("Response:", response.read().decode('utf-8'))
except Exception as e:
    print("Error:", e)
