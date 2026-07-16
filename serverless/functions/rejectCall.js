const twilio = require("twilio"); 



exports.handler = async function (context, event, callback) {

 
  const response = new Twilio.Response();
  response.appendHeader('Access-Control-Allow-Origin',  '*');
  response.appendHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  response.appendHeader('Access-Control-Allow-Headers', 'Content-Type');
  response.appendHeader('Content-Type', 'application/json');

try{

  // ── Validate inputs ──────────────────────────────────────────────────────
  const deliveryId   = (event.deliveryId || '').trim();  

  if (!deliveryId) {
    response.setStatusCode(400);
    response.setBody({ error: 'deliveryId is required' });
    return callback(null, response);
  }

  const client = context.getTwilioClient();
   const accountSid   = context.ACCOUNT_SID;
    const authToken    = context.AUTH_TOKEN_US;
    const syncBaseUrl  = 'https://sync.twilio.com/v1';
    const basicAuth     = Buffer.from(`${accountSid}:${authToken}`).toString('base64');
 
    // Small helper for calling the Sync REST API directly over HTTP
    async function syncRequest(path, method = 'GET', formParams = null) {
      const init = {
        method,
        headers: {
          Authorization: `Basic ${basicAuth}`,
        },
      };
 
      if (formParams) {
        const body = new URLSearchParams(formParams);
        init.headers['Content-Type'] = 'application/x-www-form-urlencoded';
        init.body = body.toString();
      }
 
      const res = await fetch(`${syncBaseUrl}${path}`, init);
 
      let payload = null;
      const text = await res.text();
      if (text) {
        try {
          payload = JSON.parse(text);
        } catch (e) {
          payload = text;
        }
      }
 
      if (!res.ok) {
        const err = new Error(
          (payload && payload.message) || `Sync API request failed with status ${res.status}`
        );
        err.status = res.status;
        err.body = payload;
        throw err;
      }
 
      return payload;
    }
    // ------------------------------------------------------------------
    // 1. Resolve the Queue by FriendlyName to get its QueueSid
    // ------------------------------------------------------------------
    const queues = await client.queues.list({ limit: 20 });
    const matchedQueue = queues.find((q) => q.friendlyName === deliveryId);
 
    if (!matchedQueue) {
      response.setStatusCode(404);
      response.setBody({
        success: false,
        error: `No queue found with friendly name "${deliveryId}"`,
      });
      return callback(null, response);
    }
 
    const queueSid = matchedQueue.sid;
 
    // ------------------------------------------------------------------
    // 2. Retrieve the first (front) member of the queue
    //    Twilio exposes this via the special memberSid value "Front"
    // ------------------------------------------------------------------
    let frontMember;
    try {
      frontMember = await client.queues(queueSid).members('Front').fetch();
    } catch (err) {
      // A 404 here typically means the queue is empty
      response.setStatusCode(404);
      response.setBody({
        success: false,
        error: `No members currently in queue "${deliveryId}"`,
        details: err.message,
      });
      return callback(null, response);
    }
 
    // For Queue Members, the resource's `callSid` field is the SID of the
    // call currently parked in the queue.
    const callSid = frontMember.callSid;
 
    // ------------------------------------------------------------------
    // 3. Update that call with Hangup TwiML
    // ------------------------------------------------------------------
    const hangupTwiml = '<?xml version="1.0" encoding="UTF-8"?><Response><Hangup/></Response>';
 
    const updatedCall = await client.calls(callSid).update({
      twiml: hangupTwiml,
    });
 
    // ------------------------------------------------------------------
    // 4. Create or update the Sync Document: call-status-<queueName>
    // ------------------------------------------------------------------
    const syncServiceSid = context.TWILIO_SYNC_SERVICE_SID;
    if (!syncServiceSid) {
      throw new Error(
        'SYNC_SERVICE_SID is not configured in the Function environment variables.'
      );
    }
 
    const documentUniqueName = `call-status-${deliveryId}`;
    const documentData = { disconnectReason: 'rejected by callee' };
 
    let syncDocument;
    try {
      // Try to update an existing document first
      // POST /Services/{ServiceSid}/Documents/{DocumentSidOrUniqueName}
      syncDocument = await syncRequest(
        `/Services/${syncServiceSid}/Documents/${documentUniqueName}`,
        'POST',
        { Data: JSON.stringify(documentData) }
      );
    } catch (err) {
      if (err.status === 404) {
        // Document doesn't exist yet -> create it
        // POST /Services/{ServiceSid}/Documents
        syncDocument = await syncRequest(
          `/Services/${syncServiceSid}/Documents`,
          'POST',
          {
            UniqueName: documentUniqueName,
            Data: JSON.stringify(documentData),
          }
        );
      } else {
        throw err;
      }
    }



  // ── Return token ─────────────────────────────────────────────────────────
  response.setStatusCode(200);
  response.setBody({
    status: 'success',
     });
    }catch (error) {
      console.error('Error in rejectCall function:', error);
      response.setStatusCode(500);
      response.setBody({
        status: 'error',
        message: error.message,
      });
    }

  return callback(null, response);
};