FROM gradle:8.10-jdk21
WORKDIR /src
COPY . /src
RUN gradle --no-daemon installDist
RUN chmod +x /src/wait-ca.sh
CMD ["/bin/sh", "-c", "/src/wait-ca.sh && /src/build/install/diavasi-client/bin/diavasi-client --addr \"$DIAVASI_DATA_ADDR\" --ca \"$DIAVASI_CA\" --token \"$DIAVASI_API_TOKEN\" --group demo --consumer java --total 8"]
