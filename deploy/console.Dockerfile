# Drishti console image (all front-end assets are vendored; no network needed at runtime).
#   docker build -f deploy/console.Dockerfile -t drishti-console:1.4.0 .
FROM python:3.13-slim
RUN useradd --system --uid 10001 drishti
WORKDIR /opt/drishti/console
COPY console/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY console/ .
# The help centre renders the repository's documents, so they ship with the console.
COPY docs/ /opt/drishti/docs/
COPY LICENSE CHANGELOG.md RELEASE_NOTES.md THIRD-PARTY-NOTICES.md /opt/drishti/
USER drishti
EXPOSE 17480
ENV DRISHTI_CONSOLE_HOST=0.0.0.0
HEALTHCHECK --interval=15s --timeout=3s CMD python -c "import urllib.request;urllib.request.urlopen('http://localhost:17480/healthz')" || exit 1
ENTRYPOINT ["python", "run_drishti_web.py"]
