const AccessToken = Twilio.jwt.AccessToken;
const VoiceGrant  = AccessToken.VoiceGrant;

exports.handler = function (context, event, callback) {

 
  const response = new Twilio.Response();
  response.appendHeader('Access-Control-Allow-Origin',  '*');
  response.appendHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  response.appendHeader('Access-Control-Allow-Headers', 'Content-Type');
  response.appendHeader('Content-Type', 'application/json');



  // ── Validate inputs ──────────────────────────────────────────────────────
  const identity   = (event.identity   || '').trim();

  if (!identity) {
    response.setStatusCode(400);
    response.setBody({ error: 'identity is required' });
    return callback(null, response);
  }


 
  const safeIdentity = identity.replace(/[^a-zA-Z0-9_\-\.@]/g, '_');


  const token = new AccessToken(
    context.ACCOUNT_SID,
    context.API_KEY_SID,
    context.API_SECRET,
    {
      identity: safeIdentity,
      ttl: 3600        // 1 hour — reduce as needed for production
    }
  );

  // ── Voice Grant: allow outbound calls only ───────────────────────────────
  const voiceGrant = new VoiceGrant({
    outgoingApplicationSid: context.TWIML_VOICE_APP_SID,
    incomingAllow: false    // no inbound — keeps things simple & secure
  });

  token.addGrant(voiceGrant);

  // ── Return token ─────────────────────────────────────────────────────────
  response.setStatusCode(200);
  response.setBody({
    token:    token.toJwt(),
    identity: safeIdentity
  });

  return callback(null, response);
};