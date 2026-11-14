# Drishti console image (all front-end assets are vendored; no network needed at runtime).
#   docker build -f deploy/docker/console.Dockerfile -t drishti-console:2.9.11.g .
#
# Calc's Python runtime (Pyodide) is not in git: the first stage installs it with tools/fetch-pyodide.sh (a pinned
# release, its SHA-256 checked), so the image serves it and a running console needs no internet. A runtime already
# installed in the build context (drishti-console/web/static/vendor/pyodide/, same version) is used as it is, so an offline
# build works too.
FROM python:3.13-slim AS pyodide
WORKDIR /src
COPY tools/fetch-pyodide.sh tools/fetch-pyodide.sh
COPY drishti-console/web/static/vendor/ drishti-console/web/static/vendor/
RUN bash tools/fetch-pyodide.sh && rm -rf /root/.cache/drishti

FROM python:3.13-slim
RUN useradd --system --uid 10001 drishti
WORKDIR /opt/drishti/console
COPY drishti-console/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY drishti-console/ .
COPY --from=pyodide /src/drishti-console/web/static/vendor/pyodide/ web/static/vendor/pyodide/
# The help centre renders the repository's documents, so they ship with the console.
COPY docs/ /opt/drishti/docs/
COPY config/packs/ /opt/drishti/config/packs/
COPY config/lenses/ /opt/drishti/config/lenses/
# Build -> New pack makes its bundle with the same module as `drishti.py pack bundle` (builder.tools_dir, ../tools from the console).
COPY tools/packbundle.py /opt/drishti/tools/packbundle.py
COPY LICENSE CHANGELOG.md RELEASE_NOTES.md THIRD-PARTY-NOTICES.md /opt/drishti/
# the console writes under the data folder (<repo>/data: /opt/drishti/data), its temp folder first: the one folder it may write
RUN mkdir -p /opt/drishti/data/tmp && chown -R drishti /opt/drishti/data
USER drishti
EXPOSE 17480
ENV DRISHTI_CONSOLE_HOST=0.0.0.0
HEALTHCHECK --interval=15s --timeout=3s CMD python -c "import urllib.request;urllib.request.urlopen('http://localhost:17480/healthz')" || exit 1
ENTRYPOINT ["python", "run_drishti_web.py"]
