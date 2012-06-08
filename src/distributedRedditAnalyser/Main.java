package distributedRedditAnalyser;

import java.util.ArrayList;
import java.util.List;

import weka.core.Attribute;
import weka.core.Instances;

import distributedRedditAnalyser.bolt.InstanceBolt;
import distributedRedditAnalyser.bolt.OzaBoostBolt;
import distributedRedditAnalyser.bolt.PrinterBolt;
import distributedRedditAnalyser.bolt.StatisticsBolt;
import distributedRedditAnalyser.bolt.StringToWordVectorBolt;
import distributedRedditAnalyser.spout.RawRedditSpout;
import distributedRedditAnalyser.spout.SimRedditSpout;

import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.topology.BoltDeclarer;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.utils.Utils;

/**
 * Using multiple sub-reddits tries to predict what sub-reddit a post came from based on it's topic.
 * 
 * Uses storm to distribute the task over a potential cluster
 * 
 * Built against storm 0.7.2 (https://github.com/nathanmarz/storm/tree/0.7.2) (https://github.com/downloads/nathanmarz/storm/storm-0.7.2-rc1.zip)
 * 
 * @author Luke Barnett 1109967
 * @author Tony Chen 1111377
 *
 */
public class Main {

	/**
	 * Main entry point for the program.
	 * 
	 * Takes a list of sub-reddits to perform the analysis with
	 * 
	 * @param args The sub-reddits (case sensitive) to use 
	 */
	public static void main(String[] args) {
		if(args.length == 0){
			System.err.println("No sub-reddits given, unable to continue");
			showUsage();
			System.exit(2);
		}
		if(args.length < 2){
			System.err.println("Need to provide at least two subreddits");
			showUsage();
			System.exit(2);
		}
		
		//Copy the sub-reddits into a separate list for future use with more complex arguments
		ArrayList<String> subreddits = new ArrayList<String>();
		for(int i = 0; i < args.length; i++){
			subreddits.add(args[i]);
		}
		
		//Build the Instances Header
		ArrayList<Attribute> att = new ArrayList<Attribute>();
		att.add(new Attribute("title", (List<String>)null, 0));
		att.add(new Attribute("redditClass", subreddits, 1));
		Instances instHeaders = new Instances("reddit",att, 10);
		instHeaders.setClassIndex(1);
		
		/**
		 * Start building the topology
		 * Examples can be found at: https://github.com/nathanmarz/storm-starter
		 */
		
		//Create the topology builder
		TopologyBuilder builder = new TopologyBuilder();
		
		//Add a spout for each sub-reddit
		for(String subreddit : subreddits){
			builder.setSpout("raw:" + subreddit, new RawRedditSpout(subreddit));
			builder.setBolt("instancebolt:" + subreddit , new InstanceBolt(instHeaders)).shuffleGrouping("raw:" + subreddit);
		}
		
		BoltDeclarer stringToWordVectorBolt = builder.setBolt("stringToWordBolt", new StringToWordVectorBolt(200, 80, instHeaders));
		
		for(String subreddit: subreddits){
			stringToWordVectorBolt.shuffleGrouping("instancebolt:" + subreddit);
		}
		
		//NaiveBayesMultinomial
		builder.setBolt("ozaBoostBolt:naiveBayesMultinomial", new OzaBoostBolt("bayes.NaiveBayesMultinomial")).shuffleGrouping("stringToWordBolt");
		builder.setBolt("naiveBayesMultinomialStatistics", new StatisticsBolt(subreddits.size(),50)).shuffleGrouping("ozaBoostBolt:naiveBayesMultinomial");
		
		//NaiveBayes
		builder.setBolt("ozaBoostBolt:naiveBayes", new OzaBoostBolt("bayes.NaiveBayes")).shuffleGrouping("stringToWordBolt");
		builder.setBolt("naiveBayesStatistics", new StatisticsBolt(subreddits.size(),50)).shuffleGrouping("ozaBoostBolt:naiveBayes");
		
		builder.setBolt("printerBolt", new PrinterBolt()).shuffleGrouping("naiveBayesMultinomialStatistics").shuffleGrouping("naiveBayesStatistics");
		
		//Create the configuration object
		Config conf = new Config();
		
		//Create a local cluster
		LocalCluster cluster = new LocalCluster();
		
		//Submit the topology to the cluster for execution
		cluster.submitTopology("redditAnalyser", conf, builder.createTopology());
		
		//Give a timeout period
		Utils.sleep(600000);
		
		//Kill the topology first
		cluster.killTopology("redditAnalyser");
		
		//Close the cluster
		cluster.shutdown();
		
	}
	
	/**
	 * Prints the required usage of the program
	 */
	private static void showUsage(){
		System.out.println("Usage:");
		System.out.println("java distributedRedditAnalyser.Main subreddit subreddit [subreddit...]");
	}

}
