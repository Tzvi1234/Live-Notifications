/**
 * Device registration. The FCM registration token *is* the device identity everywhere in
 * this service; `deviceId` exists only so the app has something stable to show and log.
 */

import express, { type Request, type Response, type Router } from 'express';

import type { ApiDeps } from './deps.js';
import type { DeviceRegistration } from '../store/index.js';
import type { RegisterDeviceResponse } from '../types.js';
import { optionalBodyString, requireDeviceToken, requireObjectBody } from './validation.js';

const MAX_PLATFORM = 32;
const MAX_APP_VERSION = 64;
const MAX_TIME_ZONE = 64;
const MAX_LOCALE = 32;

export function createDevicesRouter(deps: ApiDeps): Router {
  const router = express.Router();
  const logger = deps.logger.child({ component: 'routes.devices' });

  router.post('/devices', async (req: Request, res: Response) => {
    const body = requireObjectBody(req.body);
    const token = requireDeviceToken(body.token);

    // Built key by key so an absent field stays absent: `DeviceRegistration` declares bare
    // optional properties, which reject an explicit undefined under exactOptionalPropertyTypes.
    const registration: DeviceRegistration = { token };
    const platform = optionalBodyString(body.platform, 'platform', MAX_PLATFORM);
    const appVersion = optionalBodyString(body.appVersion, 'appVersion', MAX_APP_VERSION);
    const timeZone = optionalBodyString(body.timeZone, 'timeZone', MAX_TIME_ZONE);
    const locale = optionalBodyString(body.locale, 'locale', MAX_LOCALE);
    if (platform !== undefined) registration.platform = platform;
    if (appVersion !== undefined) registration.appVersion = appVersion;
    if (timeZone !== undefined) registration.timeZone = timeZone;
    if (locale !== undefined) registration.locale = locale;

    const device = await deps.store.upsertDevice(registration);

    // The token is a push credential: it identifies a device to anyone who can read the log,
    // so only the id the store minted for it is ever written out.
    logger.info('device registered', {
      deviceId: device.deviceId,
      platform: device.platform,
      appVersion: device.appVersion,
    });

    const response: RegisterDeviceResponse = { deviceId: device.deviceId, ok: true };
    res.json(response);
  });

  router.delete('/devices/:token', async (req: Request, res: Response) => {
    const token = requireDeviceToken(req.params.token, 'token');

    // Idempotent: an app that uninstalls, reinstalls and deletes again gets the same 204,
    // and so does a token this instance has never seen.
    const removed = await deps.store.deleteDevice(token);
    logger.info('device unregistered', { removed });

    res.status(204).end();
  });

  return router;
}
