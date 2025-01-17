/*
 * Copyright 2023 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.gcsio.integration;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.auth.Credentials;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorage;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageClientImpl;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageOptions;
import com.google.cloud.hadoop.gcsio.StorageResourceId;
import com.google.cloud.hadoop.gcsio.integration.GoogleCloudStorageTestHelper.TestBucketHelper;
import com.google.cloud.hadoop.util.AsyncWriteChannelOptions;
import com.google.cloud.hadoop.util.AsyncWriteChannelOptions.UploadType;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * Tests that are very specific to Java-storage client AND will nto make sense for Apiary client.
 * Any generic test which is agnostic of client-type should reside in GoogleCloudStorageImplTest
 */
public class GoogleCloudStorageClientImplIntegrationTest {

  private static final TestBucketHelper BUCKET_HELPER =
      new TestBucketHelper("dataproc-gcs-client-impl");
  private static final String TEST_BUCKET = BUCKET_HELPER.getUniqueBucketPrefix();
  private static final String TEMP_DIR_PATH = Files.createTempDir().getAbsolutePath();

  // Do cleanup the path after every test.
  private static final String GCS_WRITE_TMP_DIR =
      String.format("%s/%s", TEMP_DIR_PATH, "gcs-write-dir");
  private static final String GCS_WRITE_TMP_DIR_1 =
      String.format("%s/%s", TEMP_DIR_PATH, "gcs-write-dir-1");

  private static GoogleCloudStorage helperGcs;

  private GoogleCloudStorage gcs;

  private static ImmutableSet<String> tempDirs =
      ImmutableSet.of(GCS_WRITE_TMP_DIR_1, GCS_WRITE_TMP_DIR);
  private static ImmutableSet<Path> tempDirsPath =
      tempDirs.stream().map(x -> Paths.get(x)).collect(ImmutableSet.toImmutableSet());

  @Rule public TestName name = new TestName();

  @BeforeClass
  public static void before() throws IOException {
    helperGcs = GoogleCloudStorageTestHelper.createGcsClientImpl();
    helperGcs.createBucket(TEST_BUCKET);
  }

  @AfterClass
  public static void after() throws IOException {
    try {
      BUCKET_HELPER.cleanup(helperGcs);
    } finally {
      helperGcs.close();
    }
  }

  @Before
  public void setUp() {
    System.setProperty("java.io.tmpdir", GCS_WRITE_TMP_DIR);
  }

  @After
  public void cleanUp() {
    // cleanup any leaked files
    ImmutableSet<String> tempDirs = ImmutableSet.of(GCS_WRITE_TMP_DIR_1, GCS_WRITE_TMP_DIR);
    Iterator<String> iterator = tempDirs.stream().iterator();
    while (iterator.hasNext()) {
      String filePath = iterator.next();
      File directory = new File(filePath);
      if (directory.listFiles() != null) {
        for (File file : new File(filePath).listFiles()) {
          file.delete();
        }
      }
    }

    // close cloudStorage to free up resources
    if (gcs != null) {
      gcs.close();
    }
  }

  @Test
  public void writeToDiskDisabled() throws IOException {
    GoogleCloudStorageOptions storageOptions =
        GoogleCloudStorageTestHelper.getStandardOptionBuilder()
            .setWriteChannelOptions(
                AsyncWriteChannelOptions.builder().setUploadType(UploadType.DEFAULT).build())
            .build();

    gcs = getGCSImpl(storageOptions);
    StorageResourceId resourceId = new StorageResourceId(TEST_BUCKET, name.getMethodName());

    // validate that there were no temporaryFiles created files
    writeAndVerifyTemporaryFiles(resourceId, 0);
  }

  @Test
  public void writeToDefaultPathThenUploadEnabled() throws IOException {
    GoogleCloudStorageOptions storageOptions =
        GoogleCloudStorageTestHelper.getStandardOptionBuilder()
            .setWriteChannelOptions(
                AsyncWriteChannelOptions.builder()
                    .setUploadType(UploadType.WRITE_TO_DISK_THEN_UPLOAD)
                    .build())
            .build();

    gcs = getGCSImpl(storageOptions);
    StorageResourceId resourceId = new StorageResourceId(TEST_BUCKET, name.getMethodName());

    writeAndVerifyTemporaryFiles(resourceId, 1);
  }

  @Test
  public void writeToPathThenUploadEnabled() throws IOException {

    GoogleCloudStorageOptions storageOptions =
        GoogleCloudStorageTestHelper.getStandardOptionBuilder()
            .setWriteChannelOptions(
                AsyncWriteChannelOptions.builder()
                    .setUploadType(UploadType.WRITE_TO_DISK_THEN_UPLOAD)
                    .setTemporaryPaths(tempDirs)
                    .build())
            .build();

    gcs = getGCSImpl(storageOptions);
    StorageResourceId resourceId = new StorageResourceId(TEST_BUCKET, name.getMethodName());

    writeAndVerifyTemporaryFiles(resourceId, 1);
  }

  @Test
  public void uploadViaJournalingThrowsIfTempDirNotProvided() {

    GoogleCloudStorageOptions storageOptions =
        GoogleCloudStorageTestHelper.getStandardOptionBuilder()
            .setWriteChannelOptions(
                AsyncWriteChannelOptions.builder().setUploadType(UploadType.JOURNALING).build())
            .build();

    assertThrows(IllegalArgumentException.class, () -> getGCSImpl(storageOptions));
  }

  @Test
  public void uploadViaJournaling() throws IOException {
    GoogleCloudStorageOptions storageOptions =
        GoogleCloudStorageTestHelper.getStandardOptionBuilder()
            .setWriteChannelOptions(
                AsyncWriteChannelOptions.builder()
                    .setTemporaryPaths(tempDirs)
                    .setUploadType(UploadType.JOURNALING)
                    .build())
            .build();

    gcs = getGCSImpl(storageOptions);
    StorageResourceId resourceId = new StorageResourceId(TEST_BUCKET, name.getMethodName());

    writeAndVerifyTemporaryFiles(resourceId, 1);
  }

  private void writeAndVerifyTemporaryFiles(
      StorageResourceId resourceId, int expectedTemporaryFileCount) throws IOException {
    byte[] bytesToWrite = new byte[1024 * 1024 * 3];
    GoogleCloudStorageTestHelper.fillBytes(bytesToWrite);

    verifyTemporaryFileCount(tempDirsPath, 0);

    WritableByteChannel writeChannel = gcs.create(resourceId);
    writeChannel.write(ByteBuffer.wrap(bytesToWrite));
    // temporary files created in disk.
    verifyTemporaryFileCount(tempDirsPath, expectedTemporaryFileCount);

    writeChannel.close();
    // temporary files will be deleted from disk once upload is finished.
    verifyTemporaryFileCount(tempDirsPath, 0);
  }

  private GoogleCloudStorage getGCSImpl(GoogleCloudStorageOptions storageOptions)
      throws IOException {
    Credentials credentials = GoogleCloudStorageTestHelper.getCredentials();
    return GoogleCloudStorageClientImpl.builder()
        .setOptions(storageOptions)
        .setCredentials(credentials)
        .build();
  }

  private void verifyTemporaryFileCount(ImmutableSet<Path> paths, int expectedCount) {
    Iterator<Path> iterator = paths.stream().iterator();
    int fileCount = 0;
    while (iterator.hasNext()) {
      Path path = iterator.next();
      File directory = path.toFile();
      fileCount += getFileCount(directory);
    }
    assertThat(fileCount).isEqualTo(expectedCount);
  }

  private int getFileCount(File file) {
    File[] files = file.listFiles();
    if (files == null) {
      return 0;
    }
    int count = 0;
    for (File f : files) {
      if (f.isDirectory()) {
        count += getFileCount(f);
      } else {
        count = count + 1;
      }
    }
    return count;
  }
}
