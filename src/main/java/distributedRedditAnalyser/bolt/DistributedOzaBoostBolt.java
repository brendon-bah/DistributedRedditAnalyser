package distributedRedditAnalyser.bolt;

import java.util.Map;

import distributedRedditAnalyser.ClassifierInstance;
import distributedRedditAnalyser.OzaBoost;

import weka.core.Instances;
import weka.core.SparseInstance;

import moa.classifiers.Classifier;
import moa.core.InstancesHeader;
import moa.options.ClassOption;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;

/**
 * DistributedOzaBoost runs instances through a custom OzaBoost object that has been designed to run over multiple bolts working in parallel
 * 
 * @author Luke Barnett 1109967
 * @author Tony Chen 1111377
 *
 */
public class DistributedOzaBoostBolt extends BaseRichBolt {

	private static final long serialVersionUID = 8636714472891286811L;
	private final OzaBoost classifier;
	private final int id;
	private OutputCollector collector;
	private InstancesHeader INST_HEADERS;
	
	public DistributedOzaBoostBolt(String classifierName, int id){
		this.id = id;
		classifier = new OzaBoost();
		classifier.baseLearnerOption = new ClassOption("baseLearner", 'l',"Classifier to train.",Classifier.class, classifierName);
		classifier.ensembleSizeOption.setValue(50);
		classifier.prepareForUse();
	}

	@Override
	public void prepare(Map stormConf, TopologyContext context,	OutputCollector collector) {
		this.collector = collector;
	}

	@Override
	public void execute(Tuple input) {
		
		Object obj = input.getValue(0);
		//If we get the instance headers set them and reset
		if(obj.getClass() == Instances.class){
			INST_HEADERS = new InstancesHeader((Instances) obj);
			classifier.setModelContext(INST_HEADERS);
			classifier.resetLearningImpl();
		}else if(obj.getClass() == SparseInstance.class){
			//If it's an instance
			SparseInstance inst = (SparseInstance) obj;
			//Emit the entire prediction array and the correct value
			collector.emit(new Values(classifier.getVotesForInstance(inst), inst.classValue()));
			//Train on instance
			classifier.trainOnInstanceImpl(inst);
			//Send out our latest classifier
			ClassifierInstance latestClassifier = classifier.getLatestClassifier();
			if(latestClassifier != null)
				collector.emit(new Values(latestClassifier, (Integer)id));
		}else if(obj.getClass() == ClassifierInstance.class){
			//If it's a classifier try to add it to the ensemble
			//Make sure we aren't just adding our own classifier
			if(((Integer)input.getValue(1)).intValue() != id){
				classifier.addClassifier((ClassifierInstance)obj);
			}
		}
		collector.ack(input);
	}

	@Override
	public void declareOutputFields(OutputFieldsDeclarer declarer) {
		declarer.declare(new Fields("votesForInstance","actualClass"));
	}

}
