# Drishti console image (all front-end assets are vendored; no network needed at runtime).
#   docker build -f deploy/console.Dockerfile -t drishti-console:1.12.0 .
#
# Calc's Python runtime (Pyodide) is not in git: the first stage installs it with tools/fetch-pyodide.sh (a pinned
# release, its SHA-256 checked), so the image serves it and a running console needs no internet. A runtime already
# installed in the build context (console/web/static/vendor/pyodide/, same version) is used as it is, so an offline
# build works too.
FROM python:3.13-slim AS pyodide
WORKDIR /src
COPY tools/fetch-pyodide.sh tools/fetch-pyodide.sh
COPY console/ console/
RUN bash tools/fetch-pyodide.sh && rm -rf /root/.cache/drishti

FROM python:3.13-slim
RUN useradd --system --uid 10001 drishti
WORKDIR /opt/drishti/console
COPY console/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY --from=pyodide /src/console/ .
# The help centre renders the repository's documents, so they ship with the console.
COPY docs/ /opt/drishti/docs/
COPY packs/ /opt/drishti/packs/
COPY LICENSE CHANGELOG.md RELEASE_NOTES.md THIRD-PARTY-NOTICES.md /opt/drishti/
USER drishti
EXPOSE 17480
ENV DRISHTI_CONSOLE_HOST=0.0.0.0
HEALTHCHECK --interval=15s --timeout=3s CMD python -c "import urllib.request;urllib.request.urlopen('http://localhost:17480/healthz')" || exit 1
ENTRYPOINT ["python", "run_drishti_web.py"]
