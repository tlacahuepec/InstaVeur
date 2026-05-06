import * as admin from "firebase-admin";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {defineSecret, defineString} from "firebase-functions/params";
import {randomBytes} from "node:crypto";

admin.initializeApp();

const db = admin.firestore();
const META_APP_ID = defineString("META_APP_ID");
const META_REDIRECT_URI = defineString("META_REDIRECT_URI", {
  default: "metamediasaver://oauth/meta",
});
const META_GRAPH_VERSION = defineString("META_GRAPH_VERSION", {
  default: "v24.0",
});
const META_LOGIN_SCOPES = defineString("META_LOGIN_SCOPES", {
  default:
    "email,pages_show_list,pages_read_engagement,instagram_basic,user_photos,user_videos",
});
const META_APP_SECRET = defineSecret("META_APP_SECRET");

type SourceType = "instagram" | "facebook_user" | "facebook_page";
type MediaType = "IMAGE" | "VIDEO" | "CAROUSEL" | "UNKNOWN";

interface StoredAccount {
  id: string;
  sourceType: SourceType;
  displayName: string;
  username?: string;
  avatarUrl?: string;
  providerAccountId: string;
  accessToken: string;
  createdAt?: admin.firestore.FieldValue;
  updatedAt?: admin.firestore.FieldValue;
}

interface SanitizedAccount {
  id: string;
  sourceType: SourceType;
  displayName: string;
  username?: string;
  avatarUrl?: string;
}

interface StoredMediaItem {
  id: string;
  accountId: string;
  providerMediaId: string;
  sourceType: SourceType;
  mediaType: MediaType;
  mediaUrl?: string;
  thumbnailUrl?: string;
  caption?: string;
  permalink?: string;
  timestamp?: string;
  downloadable: boolean;
  updatedAt?: admin.firestore.FieldValue;
}

interface GraphList<T> {
  data?: T[];
  paging?: {
    cursors?: {
      after?: string;
    };
  };
}

interface MetaUserResponse {
  id: string;
  name?: string;
  email?: string;
}

interface TokenResponse {
  access_token: string;
  token_type?: string;
  expires_in?: number;
}

interface PageResponse {
  id: string;
  name?: string;
  access_token?: string;
  instagram_business_account?: {
    id: string;
    username?: string;
    name?: string;
    profile_picture_url?: string;
  };
}

interface InstagramMediaResponse {
  id: string;
  caption?: string;
  media_type?: string;
  media_url?: string;
  thumbnail_url?: string;
  permalink?: string;
  timestamp?: string;
  username?: string;
  children?: GraphList<InstagramMediaResponse>;
}

interface FacebookPhotoResponse {
  id: string;
  name?: string;
  created_time?: string;
  link?: string;
  images?: Array<{
    source?: string;
    width?: number;
    height?: number;
  }>;
}

interface FacebookVideoResponse {
  id: string;
  description?: string;
  created_time?: string;
  source?: string;
  picture?: string;
  permalink_url?: string;
}

export const startMetaConnection = onCall(async (request) => {
  const uid = requireUid(request.auth?.uid);
  const state = randomBytes(24).toString("base64url");
  const expiresAt = admin.firestore.Timestamp.fromMillis(Date.now() + 10 * 60 * 1000);

  await stateRef(uid, state).set({
    state,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    expiresAt,
  });

  const url = new URL(`https://www.facebook.com/${META_GRAPH_VERSION.value()}/dialog/oauth`);
  url.searchParams.set("client_id", META_APP_ID.value());
  url.searchParams.set("redirect_uri", META_REDIRECT_URI.value());
  url.searchParams.set("state", state);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("scope", META_LOGIN_SCOPES.value());

  return {
    authorizationUrl: url.toString(),
    state,
  };
});

export const finishMetaConnection = onCall(
  {secrets: [META_APP_SECRET]},
  async (request) => {
    const uid = requireUid(request.auth?.uid);
    const code = requireString(request.data, "code");
    const state = requireString(request.data, "state");

    const stateSnap = await stateRef(uid, state).get();
    if (!stateSnap.exists) {
      throw new HttpsError("failed-precondition", "Connection state was not found.");
    }

    const expiresAt = stateSnap.get("expiresAt") as admin.firestore.Timestamp | undefined;
    if (!expiresAt || expiresAt.toMillis() < Date.now()) {
      await stateRef(uid, state).delete();
      throw new HttpsError("deadline-exceeded", "Connection state expired.");
    }

    const shortToken = await exchangeCodeForToken(code);
    const longToken = await exchangeForLongLivedToken(shortToken.access_token);
    await upsertAccounts(uid, longToken.access_token);
    await stateRef(uid, state).delete();

    return {accounts: await listAccountsForUser(uid)};
  }
);

export const listConnectedAccounts = onCall(async (request) => {
  const uid = requireUid(request.auth?.uid);
  return {accounts: await listAccountsForUser(uid)};
});

export const listMedia = onCall(async (request) => {
  const uid = requireUid(request.auth?.uid);
  const accountId = requireString(request.data, "accountId");
  const cursor = optionalString(request.data, "cursor");
  const limit = Math.min(Math.max(Number(request.data?.limit ?? 24), 1), 48);
  const account = await readAccount(uid, accountId);

  const page = account.sourceType === "instagram" ?
    await listInstagramMedia(uid, account, cursor, limit) :
    await listFacebookMedia(uid, account, cursor, limit);

  return page;
});

export const refreshMediaUrl = onCall(async (request) => {
  const uid = requireUid(request.auth?.uid);
  const mediaId = requireString(request.data, "mediaId");
  const mediaSnap = await mediaRef(uid, mediaId).get();

  if (!mediaSnap.exists) {
    throw new HttpsError("not-found", "Media item was not found.");
  }

  const media = mediaSnap.data() as StoredMediaItem;
  const account = await readAccount(uid, media.accountId);
  const refreshed = account.sourceType === "instagram" ?
    await refreshInstagramMedia(account, media) :
    await refreshFacebookMedia(account, media);

  await mediaRef(uid, mediaId).set(
    {
      ...refreshed,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    },
    {merge: true}
  );

  return {
    mediaUrl: refreshed.mediaUrl ?? null,
    thumbnailUrl: refreshed.thumbnailUrl ?? null,
    downloadable: Boolean(refreshed.mediaUrl),
  };
});

export const disconnectMeta = onCall(async (request) => {
  const uid = requireUid(request.auth?.uid);
  const accountId = requireString(request.data, "accountId");

  await accountRef(uid, accountId).delete();
  await deleteMediaForAccount(uid, accountId);

  return {ok: true};
});

async function exchangeCodeForToken(code: string): Promise<TokenResponse> {
  return graphGet<TokenResponse>("oauth/access_token", {
    client_id: META_APP_ID.value(),
    client_secret: META_APP_SECRET.value(),
    redirect_uri: META_REDIRECT_URI.value(),
    code,
  });
}

async function exchangeForLongLivedToken(shortToken: string): Promise<TokenResponse> {
  return graphGet<TokenResponse>("oauth/access_token", {
    grant_type: "fb_exchange_token",
    client_id: META_APP_ID.value(),
    client_secret: META_APP_SECRET.value(),
    fb_exchange_token: shortToken,
  });
}

async function upsertAccounts(uid: string, accessToken: string): Promise<void> {
  const user = await graphGet<MetaUserResponse>("me", {
    fields: "id,name,email",
    access_token: accessToken,
  });

  const batch = db.batch();
  const userAccount: StoredAccount = {
    id: `facebook_user_${user.id}`,
    sourceType: "facebook_user",
    displayName: user.name ?? "Facebook user",
    username: user.email,
    providerAccountId: user.id,
    accessToken,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  };
  batch.set(accountRef(uid, userAccount.id), userAccount, {merge: true});

  const pages = await graphGet<GraphList<PageResponse>>("me/accounts", {
    fields:
      "id,name,access_token,instagram_business_account{id,username,name,profile_picture_url}",
    access_token: accessToken,
    limit: 100,
  }).catch(() => ({data: [] as PageResponse[]}));

  for (const page of pages.data ?? []) {
    const pageToken = page.access_token ?? accessToken;
    const pageAccount: StoredAccount = {
      id: `facebook_page_${page.id}`,
      sourceType: "facebook_page",
      displayName: page.name ?? "Facebook Page",
      providerAccountId: page.id,
      accessToken: pageToken,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    };
    batch.set(accountRef(uid, pageAccount.id), pageAccount, {merge: true});

    if (page.instagram_business_account?.id) {
      const ig = page.instagram_business_account;
      const instagramAccount: StoredAccount = {
        id: `instagram_${ig.id}`,
        sourceType: "instagram",
        displayName: ig.name ?? ig.username ?? page.name ?? "Instagram",
        username: ig.username,
        avatarUrl: ig.profile_picture_url,
        providerAccountId: ig.id,
        accessToken: pageToken,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      };
      batch.set(accountRef(uid, instagramAccount.id), instagramAccount, {merge: true});
    }
  }

  await batch.commit();
}

async function listAccountsForUser(uid: string): Promise<SanitizedAccount[]> {
  const snapshot = await accountCollection(uid).orderBy("displayName").get();
  return snapshot.docs.map((doc) => sanitizeAccount(doc.data() as StoredAccount));
}

async function listInstagramMedia(
  uid: string,
  account: StoredAccount,
  cursor: string | undefined,
  limit: number
): Promise<{items: StoredMediaItem[]; nextCursor?: string}> {
  const response = await graphGet<GraphList<InstagramMediaResponse>>(
    `${account.providerAccountId}/media`,
    {
      fields:
        "id,caption,media_type,media_url,thumbnail_url,permalink,timestamp,username,children{id,media_type,media_url,thumbnail_url,permalink}",
      limit,
      after: cursor,
      access_token: account.accessToken,
    }
  );

  const items = (response.data ?? []).flatMap((media) => normalizeInstagramMedia(account, media));
  await saveMediaItems(uid, items);

  return {
    items,
    nextCursor: response.paging?.cursors?.after,
  };
}

async function listFacebookMedia(
  uid: string,
  account: StoredAccount,
  cursor: string | undefined,
  limit: number
): Promise<{items: StoredMediaItem[]; nextCursor?: string}> {
  const photos = await graphGet<GraphList<FacebookPhotoResponse>>(
    `${account.providerAccountId}/photos`,
    {
      type: "uploaded",
      fields: "id,name,created_time,images,link",
      limit,
      after: cursor,
      access_token: account.accessToken,
    }
  );

  const videos = cursor ? {data: [] as FacebookVideoResponse[]} :
    await graphGet<GraphList<FacebookVideoResponse>>(
      `${account.providerAccountId}/videos`,
      {
        fields: "id,description,created_time,source,picture,permalink_url",
        limit,
        access_token: account.accessToken,
      }
    ).catch(() => ({data: [] as FacebookVideoResponse[]}));

  const photoItems = (photos.data ?? []).map((photo) => normalizeFacebookPhoto(account, photo));
  const videoItems = (videos.data ?? []).map((video) => normalizeFacebookVideo(account, video));
  const items = [...photoItems, ...videoItems]
    .sort((left, right) => (right.timestamp ?? "").localeCompare(left.timestamp ?? ""));

  await saveMediaItems(uid, items);

  return {
    items,
    nextCursor: photos.paging?.cursors?.after,
  };
}

function normalizeInstagramMedia(
  account: StoredAccount,
  media: InstagramMediaResponse
): StoredMediaItem[] {
  if (media.media_type === "CAROUSEL_ALBUM" && media.children?.data?.length) {
    return media.children.data.map((child, index) => {
      const childId = child.id ?? `${media.id}_${index}`;
      return {
        id: childId,
        providerMediaId: childId,
        accountId: account.id,
        sourceType: "instagram",
        caption: media.caption,
        mediaType: normalizeMediaType(child.media_type),
        mediaUrl: child.media_url,
        thumbnailUrl: child.thumbnail_url ?? media.thumbnail_url ?? media.media_url,
        permalink: child.permalink ?? media.permalink,
        timestamp: media.timestamp,
        downloadable: Boolean(child.media_url),
      };
    });
  }

  return [
    {
      id: media.id,
      providerMediaId: media.id,
      accountId: account.id,
      sourceType: "instagram",
      caption: media.caption,
      mediaType: normalizeMediaType(media.media_type),
      mediaUrl: media.media_url,
      thumbnailUrl: media.thumbnail_url,
      permalink: media.permalink,
      timestamp: media.timestamp,
      downloadable: Boolean(media.media_url),
    },
  ];
}

function normalizeFacebookPhoto(
  account: StoredAccount,
  photo: FacebookPhotoResponse
): StoredMediaItem {
  const image = [...(photo.images ?? [])]
    .sort((left, right) => ((right.width ?? 0) * (right.height ?? 0)) -
      ((left.width ?? 0) * (left.height ?? 0)))[0];

  return {
    id: photo.id,
    providerMediaId: photo.id,
    accountId: account.id,
    sourceType: account.sourceType,
    caption: photo.name,
    mediaType: "IMAGE",
    mediaUrl: image?.source,
    thumbnailUrl: image?.source,
    permalink: photo.link,
    timestamp: photo.created_time,
    downloadable: Boolean(image?.source),
  };
}

function normalizeFacebookVideo(
  account: StoredAccount,
  video: FacebookVideoResponse
): StoredMediaItem {
  return {
    id: video.id,
    providerMediaId: video.id,
    accountId: account.id,
    sourceType: account.sourceType,
    caption: video.description,
    mediaType: "VIDEO",
    mediaUrl: video.source,
    thumbnailUrl: video.picture,
    permalink: video.permalink_url,
    timestamp: video.created_time,
    downloadable: Boolean(video.source),
  };
}

async function refreshInstagramMedia(
  account: StoredAccount,
  media: StoredMediaItem
): Promise<Partial<StoredMediaItem>> {
  const response = await graphGet<InstagramMediaResponse>(
    media.providerMediaId,
    {
      fields: "id,caption,media_type,media_url,thumbnail_url,permalink,timestamp",
      access_token: account.accessToken,
    }
  );

  return {
    mediaUrl: response.media_url,
    thumbnailUrl: response.thumbnail_url,
    permalink: response.permalink,
    timestamp: response.timestamp,
    downloadable: Boolean(response.media_url),
  };
}

async function refreshFacebookMedia(
  account: StoredAccount,
  media: StoredMediaItem
): Promise<Partial<StoredMediaItem>> {
  if (media.mediaType === "VIDEO") {
    const response = await graphGet<FacebookVideoResponse>(
      media.providerMediaId,
      {
        fields: "source,picture,permalink_url,created_time",
        access_token: account.accessToken,
      }
    );

    return {
      mediaUrl: response.source,
      thumbnailUrl: response.picture,
      permalink: response.permalink_url,
      timestamp: response.created_time,
      downloadable: Boolean(response.source),
    };
  }

  const response = await graphGet<FacebookPhotoResponse>(
    media.providerMediaId,
    {
      fields: "images,link,created_time",
      access_token: account.accessToken,
    }
  );
  const image = [...(response.images ?? [])]
    .sort((left, right) => ((right.width ?? 0) * (right.height ?? 0)) -
      ((left.width ?? 0) * (left.height ?? 0)))[0];

  return {
    mediaUrl: image?.source,
    thumbnailUrl: image?.source,
    permalink: response.link,
    timestamp: response.created_time,
    downloadable: Boolean(image?.source),
  };
}

async function saveMediaItems(uid: string, items: StoredMediaItem[]): Promise<void> {
  if (!items.length) return;

  let batch = db.batch();
  let count = 0;

  for (const item of items) {
    batch.set(
      mediaRef(uid, item.id),
      {
        ...item,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      {merge: true}
    );
    count += 1;

    if (count === 450) {
      await batch.commit();
      batch = db.batch();
      count = 0;
    }
  }

  if (count > 0) {
    await batch.commit();
  }
}

async function deleteMediaForAccount(uid: string, accountId: string): Promise<void> {
  while (true) {
    const snapshot = await mediaCollection(uid)
      .where("accountId", "==", accountId)
      .limit(450)
      .get();

    if (snapshot.empty) return;

    const batch = db.batch();
    snapshot.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
  }
}

async function readAccount(uid: string, accountId: string): Promise<StoredAccount> {
  const snapshot = await accountRef(uid, accountId).get();
  if (!snapshot.exists) {
    throw new HttpsError("not-found", "Connected account was not found.");
  }
  return snapshot.data() as StoredAccount;
}

function sanitizeAccount(account: StoredAccount): SanitizedAccount {
  return {
    id: account.id,
    sourceType: account.sourceType,
    displayName: account.displayName,
    username: account.username,
    avatarUrl: account.avatarUrl,
  };
}

function normalizeMediaType(value?: string): MediaType {
  switch (value) {
  case "IMAGE":
    return "IMAGE";
  case "VIDEO":
  case "REELS":
    return "VIDEO";
  case "CAROUSEL_ALBUM":
    return "CAROUSEL";
  default:
    return "UNKNOWN";
  }
}

async function graphGet<T>(path: string, params: Record<string, unknown>): Promise<T> {
  const url = new URL(
    `https://graph.facebook.com/${META_GRAPH_VERSION.value()}/${path.replace(/^\/+/, "")}`
  );
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") {
      url.searchParams.set(key, String(value));
    }
  }

  const response = await fetch(url);
  const body = await response.json().catch(() => ({})) as T & {
    error?: {message?: string; type?: string};
  };

  if (!response.ok || body.error) {
    throw new HttpsError(
      "failed-precondition",
      body.error?.message ?? `Meta Graph request failed with ${response.status}.`
    );
  }

  return body as T;
}

function requireUid(uid: string | undefined): string {
  if (!uid) {
    throw new HttpsError("unauthenticated", "Sign in before calling this function.");
  }
  return uid;
}

function requireString(data: unknown, key: string): string {
  const value = optionalString(data, key);
  if (!value) {
    throw new HttpsError("invalid-argument", `${key} is required.`);
  }
  return value;
}

function optionalString(data: unknown, key: string): string | undefined {
  const record = data as Record<string, unknown> | undefined;
  const value = record?.[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function stateRef(uid: string, state: string) {
  return db.collection("metaConnectionStates").doc(uid).collection("states").doc(state);
}

function accountCollection(uid: string) {
  return db.collection("metaConnections").doc(uid).collection("accounts");
}

function accountRef(uid: string, accountId: string) {
  return accountCollection(uid).doc(accountId);
}

function mediaCollection(uid: string) {
  return db.collection("metaConnections").doc(uid).collection("mediaItems");
}

function mediaRef(uid: string, mediaId: string) {
  return mediaCollection(uid).doc(mediaId);
}
