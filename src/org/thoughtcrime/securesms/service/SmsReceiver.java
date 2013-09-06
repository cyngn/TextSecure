/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.service;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.util.Pair;

import org.thoughtcrime.securesms.crypto.DecryptingQueue;
import org.thoughtcrime.securesms.crypto.KeyExchangeProcessor;
import org.thoughtcrime.securesms.crypto.MasterSecretUtil;
import org.thoughtcrime.securesms.crypto.protocol.KeyExchangeMessage;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.EncryptingSmsDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.protocol.WirePrefix;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.IncomingEncryptedMessage;
import org.thoughtcrime.securesms.sms.IncomingKeyExchangeMessage;
import org.thoughtcrime.securesms.sms.IncomingPreKeyBundleMessage;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.thoughtcrime.securesms.sms.MultipartSmsMessageHandler;
import org.thoughtcrime.securesms.sms.SmsTransportDetails;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.textsecure.crypto.InvalidKeyException;
import org.whispersystems.textsecure.crypto.InvalidVersionException;
import org.whispersystems.textsecure.crypto.MasterSecret;
import org.whispersystems.textsecure.crypto.protocol.PreKeyBundleMessage;
import org.whispersystems.textsecure.push.PushMessage;
import org.whispersystems.textsecure.storage.InvalidKeyIdException;

import java.io.IOException;
import java.util.List;

public class SmsReceiver {

  private MultipartSmsMessageHandler multipartMessageHandler = new MultipartSmsMessageHandler();

  private final Context context;

  public SmsReceiver(Context context) {
    this.context = context;
  }

  private IncomingTextMessage assembleMessageFragments(List<IncomingTextMessage> messages, int pushType) {
    if (messages.size() != 1) return assembleMessageFragments(messages);

    IncomingTextMessage message = messages.get(0);

    switch (pushType) {
      case PushMessage.TYPE_MESSAGE_CIPHERTEXT:
        return new IncomingEncryptedMessage(message, message.getMessageBody());
      case PushMessage.TYPE_MESSAGE_PREKEY_BUNDLE:
        return new IncomingPreKeyBundleMessage(message, message.getMessageBody());
      case PushMessage.TYPE_MESSAGE_KEY_EXCHANGE:
        return new IncomingKeyExchangeMessage(message, message.getMessageBody());
    }

    return message;
  }

  private IncomingTextMessage assembleMessageFragments(List<IncomingTextMessage> messages) {
    IncomingTextMessage message = new IncomingTextMessage(messages);

    if (WirePrefix.isEncryptedMessage(message.getMessageBody()) ||
        WirePrefix.isKeyExchange(message.getMessageBody())      ||
        WirePrefix.isPreKeyBundle(message.getMessageBody()))
    {
      return multipartMessageHandler.processPotentialMultipartMessage(message);
    } else {
      return message;
    }
  }

  private Pair<Long, Long> storeSecureMessage(MasterSecret masterSecret, IncomingTextMessage message) {
    Pair<Long, Long> messageAndThreadId = DatabaseFactory.getEncryptingSmsDatabase(context)
                                                         .insertMessageInbox(masterSecret, message);

    if (masterSecret != null) {
      DecryptingQueue.scheduleDecryption(context, masterSecret, messageAndThreadId.first,
                                         messageAndThreadId.second,
                                         message.getSender(), message.getMessageBody(),
                                         message.isSecureMessage(), message.isKeyExchange());
    }

    return messageAndThreadId;
  }

  private Pair<Long, Long> storeStandardMessage(MasterSecret masterSecret, IncomingTextMessage message) {
    EncryptingSmsDatabase encryptingDatabase = DatabaseFactory.getEncryptingSmsDatabase(context);
    SmsDatabase           plaintextDatabase  = DatabaseFactory.getSmsDatabase(context);

    if (masterSecret != null) {
      return encryptingDatabase.insertMessageInbox(masterSecret, message);
    } else if (MasterSecretUtil.hasAsymmericMasterSecret(context)) {
      return encryptingDatabase.insertMessageInbox(MasterSecretUtil.getAsymmetricMasterSecret(context, null), message);
    } else {
      return plaintextDatabase.insertMessageInbox(message);
    }
  }

  private Pair<Long, Long> storePreKeyBundledMessage(MasterSecret masterSecret,
                                                     IncomingKeyExchangeMessage message)
  {
    Log.w("SmsReceiver", "Processing prekey message...");

    try {
      Recipient            recipient        = new Recipient(null, message.getSender(), null, null);
      KeyExchangeProcessor processor        = new KeyExchangeProcessor(context, masterSecret, recipient);
      SmsTransportDetails  transportDetails = new SmsTransportDetails();
      PreKeyBundleMessage  preKeyExchange   = new PreKeyBundleMessage(transportDetails.getDecodedMessage(message.getMessageBody().getBytes()));

      if (processor.isTrusted(preKeyExchange)) {
        processor.processKeyExchangeMessage(preKeyExchange);

        String                   bundledMessageBody = new String(transportDetails.getEncodedMessage(preKeyExchange.getBundledMessage()));
        IncomingEncryptedMessage bundledMessage     = new IncomingEncryptedMessage(message, bundledMessageBody);
        Pair<Long, Long>         messageAndThreadId = storeSecureMessage(masterSecret, bundledMessage);

        Intent intent = new Intent(KeyExchangeProcessor.SECURITY_UPDATE_EVENT);
        intent.putExtra("thread_id", messageAndThreadId.second);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent, KeyCachingService.KEY_PERMISSION);

        return messageAndThreadId;
      } else {
        /// XXX
      }
    } catch (InvalidKeyException e) {
      Log.w("SmsReceiver", e);
      message.setCorrupted(true);
    } catch (InvalidVersionException e) {
      Log.w("SmsReceiver", e);
      message.setInvalidVersion(true);
    } catch (InvalidKeyIdException e) {
      Log.w("SmsReceiver", e);
      message.setStale(true);
    } catch (IOException e) {
      Log.w("SmsReceive", e);
      message.setCorrupted(true);
    }

    return storeStandardMessage(masterSecret, message);
  }

  private Pair<Long, Long> storeKeyExchangeMessage(MasterSecret masterSecret,
                                                   IncomingKeyExchangeMessage message)
  {
    if (masterSecret != null && TextSecurePreferences.isAutoRespondKeyExchangeEnabled(context)) {
      try {
        Recipient recipient                   = new Recipient(null, message.getSender(), null, null);
        KeyExchangeMessage keyExchangeMessage = new KeyExchangeMessage(message.getMessageBody());
        KeyExchangeProcessor processor        = new KeyExchangeProcessor(context, masterSecret, recipient);

        Log.w("SmsReceiver", "Received key with fingerprint: " + keyExchangeMessage.getPublicKey().getFingerprint());

        if (processor.isStale(keyExchangeMessage)) {
          message.setStale(true);
        } else if (processor.isTrusted(keyExchangeMessage)) {
          message.setProcessed(true);

          Pair<Long, Long> messageAndThreadId = storeStandardMessage(masterSecret, message);
          processor.processKeyExchangeMessage(keyExchangeMessage, messageAndThreadId.second);

          return messageAndThreadId;
        }
      } catch (InvalidVersionException e) {
        Log.w("SmsReceiver", e);
        message.setInvalidVersion(true);
      } catch (InvalidKeyException e) {
        Log.w("SmsReceiver", e);
        message.setCorrupted(true);
      }
    }

    return storeStandardMessage(masterSecret, message);
  }

  private Pair<Long, Long> storeMessage(MasterSecret masterSecret, IncomingTextMessage message) {
    if      (message.isSecureMessage()) return storeSecureMessage(masterSecret, message);
    else if (message.isPreKeyBundle())  return storePreKeyBundledMessage(masterSecret, (IncomingKeyExchangeMessage) message);
    else if (message.isKeyExchange())   return storeKeyExchangeMessage(masterSecret, (IncomingKeyExchangeMessage) message);
    else                                return storeStandardMessage(masterSecret, message);
  }

  private void handleReceiveMessage(MasterSecret masterSecret, Intent intent) {
    List<IncomingTextMessage> messagesList = intent.getExtras().getParcelableArrayList("text_messages");
    int                       pushType     = intent.getIntExtra("push_type", -1);

    IncomingTextMessage message;

    if (pushType != -1) message = assembleMessageFragments(messagesList, pushType);
    else                message = assembleMessageFragments(messagesList);

    if (message != null) {
      Pair<Long, Long> messageAndThreadId = storeMessage(masterSecret, message);
      MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
    }
  }

  public void process(MasterSecret masterSecret, Intent intent) {
    if (intent.getAction().equals(SendReceiveService.RECEIVE_SMS_ACTION)) {
      handleReceiveMessage(masterSecret, intent);
    }
  }
}
