/*
 * Copyright (c) 2015 The Ontario Institute for Cancer Research. All rights reserved.                             
 *                                                                                                               
 * This program and the accompanying materials are made available under the terms of the GNU Public License v3.0.
 * You should have received a copy of the GNU General Public License along with                                  
 * this program. If not, see <http://www.gnu.org/licenses/>.                                                     
 *                                                                                                               
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY                           
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES                          
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT                           
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,                                
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED                          
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;                               
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER                              
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN                         
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.icgc.dcc.release.job.join.task;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.String.format;
import static org.icgc.dcc.common.core.model.FieldNames.SubmissionFieldNames.SUBMISSION_ANALYZED_SAMPLE_ID;
import static org.icgc.dcc.common.core.model.FieldNames.SubmissionFieldNames.SUBMISSION_OBSERVATION_ANALYSIS_ID;
import static org.icgc.dcc.release.core.util.Keys.getKey;
import static org.icgc.dcc.release.job.join.utils.Tasks.resolveDonorSamples;

import java.util.Map;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.val;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.broadcast.Broadcast;
import org.icgc.dcc.release.core.job.FileType;
import org.icgc.dcc.release.core.task.GenericTask;
import org.icgc.dcc.release.core.task.TaskContext;
import org.icgc.dcc.release.core.util.SparkWorkaroundUtils;
import org.icgc.dcc.release.job.join.function.EnrichPrimaryMeta;
import org.icgc.dcc.release.job.join.function.KeyAnalysisIdAnalyzedSampleIdField;
import org.icgc.dcc.release.job.join.model.DonorSample;

import com.fasterxml.jackson.databind.node.ObjectNode;

@RequiredArgsConstructor
public class PrimaryMetaJoinTask extends GenericTask {

  private static final String META_FILE_TYPE_SUFFIX = "_M";
  private static final String OUTPUT_FILE_TYPE_SUFFIX = "";
  private static final String PRIMARY_FILE_TYPE_REGEX = "_P(_(\\w)*)*$";

  @NonNull
  private final Broadcast<Map<String, Map<String, DonorSample>>> donorSamplesbyProject;
  @NonNull
  protected final FileType primaryFileType;

  @Override
  public void execute(TaskContext taskContext) {
    val output = joinPrimaryMeta(taskContext);

    writeOutput(taskContext, output, resolveOutputFileType(primaryFileType));
  }

  @Override
  public String getName() {
    return format("%s(%s)", super.getName(), resolveOutputFileType(primaryFileType).getId());
  }

  protected JavaRDD<ObjectNode> joinPrimaryMeta(TaskContext taskContext) {
    val primary = parsePrimary(primaryFileType, taskContext);
    val meta = parseMeta(resolveMetaFileType(primaryFileType), taskContext);
    val donorSamples = resolveDonorSamples(taskContext, donorSamplesbyProject);
    val output = join(primary, meta, donorSamples, taskContext);

    return output;
  }

  private JavaRDD<ObjectNode> join(JavaRDD<ObjectNode> primary, JavaRDD<ObjectNode> meta,
      Map<String, DonorSample> donorSamples, TaskContext taskContext) {
    val keyFunction = new KeyAnalysisIdAnalyzedSampleIdField();
    val outputFileType = resolveOutputFileType(primaryFileType);
    val type = outputFileType.getId();

    // Meta file type is small. We are going to put it in memory and distribute between the workers.
    val metaPairs = meta.mapToPair(keyFunction).collectAsMap();
    final Broadcast<Map<String, ObjectNode>> metaPairsBroadcast = taskContext
        .getSparkContext()
        .broadcast(SparkWorkaroundUtils.toHashMap(metaPairs));

    return joinPrimaryMeta(primary, metaPairsBroadcast)
        .map(new EnrichPrimaryMeta(type, donorSamples));
  }

  private static JavaRDD<ObjectNode> joinPrimaryMeta(
      JavaRDD<ObjectNode> primary,
      Broadcast<Map<String, ObjectNode>> metaPairsBroadcast) {
    return primary
        .map(p -> {
          String key = getKey(p, SUBMISSION_OBSERVATION_ANALYSIS_ID, SUBMISSION_ANALYZED_SAMPLE_ID);
          ObjectNode metaValue = metaPairsBroadcast.value().get(key);
          checkState(metaValue != null, "A primary record must have a corresponding record in the meta file");
          p.setAll(metaValue);

          return p;
        });
  }

  private JavaRDD<ObjectNode> parsePrimary(FileType primaryFileType, TaskContext taskContext) {
    return readInput(taskContext, primaryFileType);
  }

  private JavaRDD<ObjectNode> parseMeta(FileType metaFileType, TaskContext taskContext) {
    return readInput(taskContext, metaFileType);
  }

  protected static FileType resolveOutputFileType(FileType primaryFileType) {
    return resolveFileType(primaryFileType, OUTPUT_FILE_TYPE_SUFFIX);
  }

  private static FileType resolveMetaFileType(FileType primaryFileType) {
    return resolveFileType(primaryFileType, META_FILE_TYPE_SUFFIX);
  }

  protected static FileType resolveFileType(FileType primaryFileType, String outputFileTypeSuffix) {
    val outputName = primaryFileType.name().replaceAll(PRIMARY_FILE_TYPE_REGEX, outputFileTypeSuffix);

    return FileType.getFileType(outputName);
  }

}
