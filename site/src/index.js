// The app was renamed from POV Rajput to Nabrio: pov.rajput.cc now sends every request (with its path, so short
// links such as /tv and /discord keep working) to the same page on nabrio.rajput.cc. Everything else is static files.
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.hostname === "pov.rajput.cc") {
      url.hostname = "nabrio.rajput.cc";
      return Response.redirect(url.toString(), 301);
    }
    return env.ASSETS.fetch(request);
  },
};
