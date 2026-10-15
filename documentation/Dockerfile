# Build the static output with npm run build before creating this image.
FROM nginxinc/nginx-unprivileged:1.30.6-alpine@sha256:782bb89768cc87b7f95c18b95baa1af250e5d451f26794f59363540161147c01
ARG SOURCE_REVISION
LABEL org.opencontainers.image.title="Gameómetro documentation" \
      org.opencontainers.image.source="https://github.com/rubhern/videogame-platform" \
      org.opencontainers.image.revision="${SOURCE_REVISION}"
USER root
RUN rm -f /usr/share/nginx/html/index.html /usr/share/nginx/html/50x.html
COPY nginx.conf /etc/nginx/nginx.conf
COPY dist/ /usr/share/nginx/html/
USER 101:101
EXPOSE 8080
ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]
