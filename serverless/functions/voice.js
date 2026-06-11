exports.handler = function (context, event, callback) {

  const deliveryId = (event.deliveryId || '').trim();
  const mode = (event.mode || '').trim().toLowerCase();

  const twiml = new Twilio.twiml.VoiceResponse();

  if (!deliveryId) {
    // Safety fallback — should not happen if Android app always sends it
    twiml.say('Missing delivery ID. Goodbye.');
    twiml.hangup();
    return callback(null, twiml);
  }




  const voiceQueueName = deliveryId.replace(/[^a-zA-Z0-9_\-\.]/g, '_').substring(0, 128);

  if(mode==='caller'){
      twiml.enqueue({waitUrl:'https://handler.twilio.com/twiml/EH10fc9b66767e0f70d022428ef697836e'},voiceQueueName);
  }
  else{
    const dial = twiml.dial();
    dial.queue({},voiceQueueName);
  }


  return callback(null, twiml);
};