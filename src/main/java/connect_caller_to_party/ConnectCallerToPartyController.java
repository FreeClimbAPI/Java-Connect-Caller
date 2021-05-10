/* 
 * NOTE: Testing this tutorial requires 2 phone numbers, referenced as agentPhoneNumber and CALLER_PHONE
 *
 * 1. SET agentPhoneNumber WITH A PHONE NUMBER (IN E.164 FORMAT) CAPABLE OF ACCEPTING PHONE CALLS OR CREATE lookupAgentPhoneNumber() 
 *    FUNCTION agentPhoneNumber
 * 2. RUN PROJECT WITH COMMAND: 
 *    `gradle build && java -Dserver.port=0080 -jar build/libs/gs-spring-boot-0.1.0.jar`
 * 3. USING CALLER_PHONE, CALL FreeClimb NUMBER ASSOCIATED WITH THE ACCOUNT (CONFIGURED IN FreeClimb DASHBOARD)
 * 4. EXPECT agentPhoneNumber TO RECEIVE CALL FROM FreeClimb NUMBER ASSOCIATED WITH THE ACCOUNT: 
 * 5. EXPECT agentPhoneNumber and CALLER_PHONE SHOULD BE IN A CONFERENCE CALL
*/

package main.java.connect_caller_to_party;

import org.springframework.web.bind.annotation.RestController;
import com.vailsys.freeclimb.percl.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.vailsys.freeclimb.api.FreeClimbException;
import com.vailsys.freeclimb.webhooks.conference.ConferenceCreateActionCallback;

import com.vailsys.freeclimb.webhooks.percl.OutDialActionCallback;

import com.vailsys.freeclimb.webhooks.StatusCallback;
import com.vailsys.freeclimb.api.call.CallStatus;

import com.vailsys.freeclimb.webhooks.conference.LeaveConferenceUrlCallback;

import com.vailsys.freeclimb.api.FreeClimbClient;
import com.vailsys.freeclimb.api.conference.ConferenceUpdateOptions;
import com.vailsys.freeclimb.api.conference.ConferenceStatus;

@RestController
public class ConnectCallerToPartyController {
  private String baseUrl = System.getenv("HOST");

  // To properly communicate with FreeClimb's API, set your FreeClimb app's
  // VoiceURL endpoint to '{yourApplicationURL}/InboundCall' for this example
  // Your FreeClimb app can be configured in the FreeClimb Dashboard
  @RequestMapping(value = {
      "/InboundCall" }, method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
  public ResponseEntity<?> inboundCall() {
    // Create an empty PerCL script container
    PerCLScript script = new PerCLScript();

    // Create a conference once an inbound call has been received
    script.add(new CreateConference(baseUrl + "/ConferenceCreated"));

    // Convert PerCL container to JSON and append to response
    return new ResponseEntity<>(script.toJson(), HttpStatus.OK);
  }

  @RequestMapping(value = {
      "/ConferenceCreated" }, method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
  public ResponseEntity<?> conferenceCreated(@RequestBody String str) {
    PerCLScript script = new PerCLScript();

    ConferenceCreateActionCallback conferenceCreateActionCallback;
    try {
      conferenceCreateActionCallback = ConferenceCreateActionCallback.createFromJson(str);
      String conferenceId = conferenceCreateActionCallback.getConferenceId();

      script.add(new Say("Please wait while we attempt to connect you to an agent."));

      // Make OutDial request once conference has been created
      String agentPhoneNumber = ""; // lookupAgentPhoneNumber(); // implementation of
      // lookupAgentPhoneNumber() is left up to the developer
      OutDial outDial = new OutDial(agentPhoneNumber, conferenceCreateActionCallback.getFrom(),
          baseUrl + "/OutboundCallMade" + "/" + conferenceId, baseUrl + "/OutboundCallConnected" + "/" + conferenceId);
      outDial.setIfMachine(OutDialIfMachine.HANGUP);
      script.add(outDial);

    } catch (FreeClimbException pe) {
      System.out.print(pe);
    }

    return new ResponseEntity<>(script.toJson(), HttpStatus.OK);
  }

  @RequestMapping(value = {
      "/OutboundCallMade/{conferenceId}" }, method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
  public ResponseEntity<?> outboundCallMade(@PathVariable String conferenceId, @RequestBody String str) {
    PerCLScript script = new PerCLScript();

    OutDialActionCallback outDialActionCallback;
    try {
      // Convert JSON into a call status callback object
      outDialActionCallback = OutDialActionCallback.createFromJson(str);
      // Add initial caller to conference
      AddToConference addToConference = new AddToConference(conferenceId, outDialActionCallback.getCallId());

      // set the leaveConferenceUrl for the inbound caller, so that we can terminate
      // the conference when they hang up
      addToConference.setLeaveConferenceUrl(baseUrl + "/LeftConference");
      script.add(addToConference);

    } catch (FreeClimbException pe) {
      System.out.print(pe);
    }

    return new ResponseEntity<>(script.toJson(), HttpStatus.OK);
  }

  @RequestMapping(value = {
      "/OutboundCallConnected/{conferenceId}" }, method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
  public ResponseEntity<?> outboundCallConnected(@PathVariable String conferenceId, @RequestBody String str) {
    PerCLScript script = new PerCLScript();

    StatusCallback statusCallback;
    try {
      // Convert JSON into a call status callback object
      statusCallback = StatusCallback.fromJson(str);

      // Terminate conference if agent does not answer the call. Can't use PerCL
      // command since PerCL is ignored if the call was not answered.
      if (statusCallback.getCallStatus() != CallStatus.IN_PROGRESS) {
        terminateConference(conferenceId);
        return new ResponseEntity<>(script.toJson(), HttpStatus.OK);
      }

      script.add(new AddToConference(conferenceId, statusCallback.getCallId()));
    } catch (FreeClimbException pe) {
      System.out.print(pe);
    }

    return new ResponseEntity<>(script.toJson(), HttpStatus.OK);
  }

  @RequestMapping(value = {
      "/LeftConference" }, method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
  public ResponseEntity<?> leftConference(@RequestBody String str) {
    LeaveConferenceUrlCallback leaveConferenceUrlCallback;
    try {
      // Convert JSON into a leave conference callback object
      leaveConferenceUrlCallback = LeaveConferenceUrlCallback.createFromJson(str);
      // Terminate the conference when the initial caller hangs up. Can't use PerCL
      // command since PerCL is ignored if the caller hangs up.
      terminateConference(leaveConferenceUrlCallback.getConferenceId());

    } catch (FreeClimbException pe) {
      System.out.print(pe);
    }

    return new ResponseEntity<>("", HttpStatus.OK);
  }

  private static void terminateConference(String conferenceId) throws FreeClimbException {
    String accountId = System.getenv("ACCOUNT_ID");
    String apiKey = System.getenv("API_KEY");
    FreeClimbClient client = new FreeClimbClient(accountId, apiKey);

    // Create the ConferenceUpdateOptions and set the status to terminated
    ConferenceUpdateOptions conferenceUpdateOptions = new ConferenceUpdateOptions();
    conferenceUpdateOptions.setStatus(ConferenceStatus.TERMINATED);
    client.conferences.update(conferenceId, conferenceUpdateOptions);
  }
}