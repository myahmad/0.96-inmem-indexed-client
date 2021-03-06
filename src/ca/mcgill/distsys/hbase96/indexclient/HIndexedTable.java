package ca.mcgill.distsys.hbase96.indexclient;

import ca.mcgill.distsys.hbase96.indexcommons.IndexedColumn;
import ca.mcgill.distsys.hbase96.indexcommons.ResultComparator;
import ca.mcgill.distsys.hbase96.indexcommons.SecondaryIndexConstants;
import ca.mcgill.distsys.hbase96.indexcommons.Util;
import ca.mcgill.distsys.hbase96.indexcommons.exceptions.IndexAlreadyExistsException;
import ca.mcgill.distsys.hbase96.indexcommons.exceptions.IndexNotExistsException;
import ca.mcgill.distsys.hbase96.indexcommons.exceptions.IndexNotFoundException;
import ca.mcgill.distsys.hbase96.indexcommons.proto.Column;
import ca.mcgill.distsys.hbase96.indexcommons.proto.IndexedColumnQuery;
import ca.mcgill.distsys.hbase96.indexcoprocessors.inmem.pluggableIndex.AbstractPluggableIndex;
import ca.mcgill.distsys.hbase96.indexcoprocessors.inmem.protobuf.generated.IndexCoprocessorInMem.IndexCoprocessorInMemService;
import com.google.protobuf.ServiceException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.HTablePool;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;

import javax.crypto.CipherOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Modified by Cong
//import org.apache.hadoop.hbase.exceptions.MasterNotRunningException;
//import org.apache.hadoop.hbase.exceptions.ZooKeeperConnectionException;

public class HIndexedTable extends HTable {

	private static final int DELETE_INDEX = 0;
	private static final int CREATE_INDEX = 1;

	private static Log LOG = LogFactory.getLog(HIndexedTable.class);

  public HIndexedTable(byte[] tableName, HConnection connection,
			ExecutorService pool) throws IOException {
		super(tableName, connection, pool);
	}

	public HIndexedTable(Configuration conf, byte[] tableName)
	throws IOException {
		this(tableName, HConnectionManager.createConnection(conf),
        Executors.newCachedThreadPool());
	}

	public HIndexedTable(Configuration conf, String tableName)
	throws IOException {
		this(conf, Bytes.toBytes(tableName));
	}

	/**
	 * Creates an index on this table's family:qualifier column.
	 *
	 * @param family
	 * @param qualifier
	 * @throws Throwable
	 * @throws ServiceException
	 */
	// Modified by Cong
	@Deprecated
	public void createIndex(String family, String qualifier,
			Class<? extends AbstractPluggableIndex> indexClass,
			Object[] arguments)
			throws ServiceException, Throwable {
		createIndex(Bytes.toBytes(family), Bytes.toBytes(qualifier),
				indexClass, arguments);
	}

	/**
	 * Creates an index on this table's family:qualifier column.
	 *
	 * @param family
	 * @param qualifier
	 * @throws Throwable
	 * @throws ServiceException
	 */
	// Modified by Cong
	@Deprecated
	public void createIndex(byte[] family, byte[] qualifier,
			Class<? extends AbstractPluggableIndex> indexClass,
			Object[] arguments)
			throws ServiceException, Throwable {
		Column column = new Column(family, qualifier);
		createIndex(column, indexClass, arguments);
	}

	public void createIndex(Column column,
			Class<? extends AbstractPluggableIndex> indexClass,
			Object[] arguments)
	throws ServiceException, Throwable {
		createIndex(Arrays.asList(column), indexClass, arguments);
	}

	/**
	 * Creates a multi-column index on this table.
	 *
	 * @param columns    list of columns to create index on
	 * @param indexClass type of index (hash, hybrid, etc.)
	 * @throws Throwable
	 * @throws ServiceException
	 */
	public void createIndex(List<Column> columns,
			Class<? extends AbstractPluggableIndex> indexClass,
			Object[] arguments)
  throws ServiceException, Throwable {

		// Sort the list according to the concatenation of family and qualifier
		//Collections.sort(colList);

		// Yousuf: temp fix
		String indexClassString;
    if (indexClass == null) {
      indexClassString = SecondaryIndexConstants.HTABLE_INDEX;
    } else {
      indexClassString = indexClass.toString().split(" ")[1];
    }
		//

		CreateIndexCallable callable = new CreateIndexCallable(columns,
				indexClassString, arguments);
		Map<byte[], Boolean> results = null;

		checkSecondaryIndexMasterTable();

		updateMasterIndexTable(columns, indexClassString, arguments, CREATE_INDEX);

    if (indexClassString.equals(SecondaryIndexConstants.HTABLE_INDEX)) {
      createIndexTable(getIndexTableName(columns));
    }
    else {
      results = this.coprocessorService(IndexCoprocessorInMemService.class,
          HConstants.EMPTY_START_ROW, HConstants.LAST_ROW, callable);

      if (results != null) {
        for (byte[] regionName : results.keySet()) {
          if (!results.get(regionName)) {
            LOG.error("Region [" + Bytes.toString(regionName)
                + "] failed to create the requested index.");
          }
        }
      }
    }
	}

	/**
	 * Deletes an index on this table's family:qualifier column.
	 *
	 * @param family
	 * @param qualifier
	 * @throws Throwable
	 * @throws ServiceException
	 */
	@Deprecated
	public void deleteIndex(byte[] family, byte[] qualifier)
			throws ServiceException, Throwable {
		deleteIndex(new Column(family, qualifier));
	}

	/**
	 * Deletes an index on this table's column.
	 *
	 * @param column
	 * @throws Throwable
	 * @throws ServiceException
	 */
	public void deleteIndex(Column column)
			throws ServiceException, Throwable {
		deleteIndex(Arrays.asList(column));
	}

	/**
	 * Deletes an index on these columns.
	 *
	 * @param columns
	 * @throws Throwable
	 * @throws ServiceException
	 */
	public void deleteIndex(List<Column> columns)
			throws ServiceException, Throwable {

		DeleteIndexCallable callable = new DeleteIndexCallable(columns);
		Map<byte[], Boolean> results = null;

		checkSecondaryIndexMasterTable();

		String indexClassString =
        updateMasterIndexTable(columns, null, null, DELETE_INDEX);

    if (indexClassString != null &&
        indexClassString.equals(SecondaryIndexConstants.HTABLE_INDEX)) {
      deleteIndexTable(getIndexTableName(columns));
    }
    else {
      results = this.coprocessorService(IndexCoprocessorInMemService.class,
          HConstants.EMPTY_START_ROW, HConstants.LAST_ROW, callable);

      if (results != null) {
        for (byte[] regionName : results.keySet()) {
          if (!results.get(regionName)) {
            LOG.error("Region [" + Bytes.toString(regionName)
                + "] failed to delete the requested index.");
          }
        }
      }
    }
	}

	public List<Result> execIndexedQuery(IndexedColumnQuery query)
	throws ServiceException, Throwable {
    long startTime = System.nanoTime();

		IndexedQueryCallable callable = new IndexedQueryCallable(query);

		Map<byte[], List<Result>> resultMap = null;
		List<Result> result = new ArrayList<Result>();

		resultMap = this.coprocessorService(IndexCoprocessorInMemService.class,
				HConstants.EMPTY_START_ROW, HConstants.LAST_ROW, callable);

		if (resultMap != null) {
			for (List<Result> regionResult : resultMap.values()) {
				result.addAll(regionResult);
			}
      if (SecondaryIndexConstants.SORT_INDEXED_QUERY_RESULTS) {
			  Collections.sort(result, new ResultComparator());
      }
		}

    long duration = (System.nanoTime() - startTime) / 1000;
    //LOG.trace("execIndexedQuery: " + duration + " us");
		return result;
	}

  // Returns index type class string
	private String updateMasterIndexTable(List<Column> columns, String indexClass,
			Object[] arguments, int operation)
	throws IOException {

		HTable masterIdxTable = null;

		byte[] indexName = Bytes.toBytes(Util.concatColumnsToString(columns));

    try {
			masterIdxTable = new HTable(getConfiguration(),
					SecondaryIndexConstants.MASTER_INDEX_TABLE_NAME);

			if (operation == CREATE_INDEX) {
				Get idxGet = new Get(getTableName());
				idxGet.addColumn(Bytes.toBytes(
						SecondaryIndexConstants.MASTER_INDEX_TABLE_IDXCOLS_CF_NAME),
						indexName);
				Result rs = masterIdxTable.get(idxGet);

				if (!rs.isEmpty()) {
					String message = "Index already exists for "
							+ Bytes.toString(indexName)
							+ " of table " + Bytes.toString(getTableName());
					LOG.warn(message);
					throw new IndexAlreadyExistsException(message);
				}

				Put idxPut = new Put(getTableName());
				IndexedColumn ic = new IndexedColumn(columns);
				ic.setIndexType(indexClass);
				ic.setArguments(arguments);
				idxPut.add(Bytes.toBytes(SecondaryIndexConstants.MASTER_INDEX_TABLE_IDXCOLS_CF_NAME),
						indexName, Util.serialize(ic));
				masterIdxTable.put(idxPut);

			} else if (operation == DELETE_INDEX) {
				// Modified by Cong
				Get idxGet = new Get(getTableName());
				idxGet.addColumn(Bytes.toBytes(SecondaryIndexConstants.MASTER_INDEX_TABLE_IDXCOLS_CF_NAME),
						indexName);
				Result rs = masterIdxTable.get(idxGet);

				if (rs.isEmpty()) {
					String message = "Index doesn't exist for "
							+ Bytes.toString(indexName)
							+ " of table " + Bytes.toString(getTableName());
					LOG.warn(message);
					throw new IndexNotExistsException(message);
				}

        try {
          byte[] icb = rs.getValue(
              Bytes.toBytes(SecondaryIndexConstants.MASTER_INDEX_TABLE_IDXCOLS_CF_NAME),
              indexName);
          IndexedColumn ic = (IndexedColumn) Util.deserialize(icb);
          indexClass = ic.getIndexType();
        } catch (ClassNotFoundException ex) {
          LOG.warn(ex);
        }

				Delete idxDelete = new Delete(getTableName());
				idxDelete.deleteColumn(Bytes.toBytes(
						SecondaryIndexConstants.MASTER_INDEX_TABLE_IDXCOLS_CF_NAME),
						indexName);
				masterIdxTable.delete(idxDelete);

			} else {
				throw new UnsupportedOperationException(
						"Unknown index operation type.");
			}

		} finally {
			if (masterIdxTable != null) {
				masterIdxTable.close();
			}
		}
    return indexClass;
	}

	// private void checkSecondaryIndexMasterTable() throws
	// MasterNotRunningException, ZooKeeperConnectionException, IOException {

	private void checkSecondaryIndexMasterTable() throws IOException {
		HBaseAdmin admin = null;
		try {
			admin = new HBaseAdmin(getConfiguration());
			if (!admin.tableExists(SecondaryIndexConstants.MASTER_INDEX_TABLE_NAME)) {
				HTableDescriptor desc = new HTableDescriptor(
						SecondaryIndexConstants.MASTER_INDEX_TABLE_NAME);
				desc.addFamily(new HColumnDescriptor(
						SecondaryIndexConstants.MASTER_INDEX_TABLE_IDXCOLS_CF_NAME));
				admin.createTable(desc);
			}
		} finally {
			if (admin != null) {
				admin.close();
			}
		}
	}

	// Yousuf
  public void createHTableIndex(Column column)
  throws Throwable {
    //Class indexClass = Class.forName(SecondaryIndexConstants.HTABLE_INDEX);
    Object[] arguments = {column.getFamily(), column.getQualifier()};
    createIndex(column, null, arguments);
  }

  public void createHashTableIndex(Column column)
  throws Throwable {
		Class indexClass = Class.forName(SecondaryIndexConstants.HASHTABLE_INDEX);
		int maxTreeSize = getConfiguration().getInt(
				SecondaryIndexConstants.PRIMARYKEY_TREE_MAX_SIZE,
				SecondaryIndexConstants.PRIMARYKEY_TREE_MAX_SIZE_DEFAULT);
		Object[] arguments = {maxTreeSize, Arrays.asList(column)};
		createIndex(column, indexClass, arguments);
	}

	public void createHybridIndex(Column column)
	throws Throwable {
		Class indexClass = Class.forName(SecondaryIndexConstants.HYBRID_INDEX);
		Object[] arguments = {column.getFamily(), column.getQualifier()};
		createIndex(column, indexClass, arguments);
	}

  public void createHybridIndex2(Column column)
      throws Throwable {
    Class indexClass = Class.forName(SecondaryIndexConstants.HYBRID_INDEX2);
    Object[] arguments = {column.getFamily(), column.getQualifier()};
    createIndex(column, indexClass, arguments);
  }

  public void createIndex(Column column)
	throws Throwable {
		Class indexClass = Class.forName(SecondaryIndexConstants.DEFAULT_INDEX);
		Object[] arguments = {column.getFamily(), column.getQualifier()};
		createIndex(column, indexClass, arguments);
	}

	// Multi-column indexing only works with HashTable index
	public void createIndex(List<Column> columns)
			throws Throwable {
		Class indexClass = Class.forName(SecondaryIndexConstants.HASHTABLE_INDEX);
		int maxTreeSize = getConfiguration().getInt(
				SecondaryIndexConstants.PRIMARYKEY_TREE_MAX_SIZE,
				SecondaryIndexConstants.PRIMARYKEY_TREE_MAX_SIZE_DEFAULT);
		Object[] arguments = {maxTreeSize, columns};
		createIndex(columns, indexClass, arguments);
	}
	//

  // HTable Index
  private byte[] getIndexTableName(List<Column> columns){
    // For now we only support single-column indexes
    Column column = columns.get(0);
    return Util.getSecondaryIndexTableName(this.getTableName(), column);
  }

  private void createIndexTable(byte[] idxTableName)
  throws IOException {
    HBaseAdmin admin = null;
    try {
      admin = new HBaseAdmin(getConfiguration());
      if (!admin.tableExists(idxTableName)) {
        HTableDescriptor desc = new HTableDescriptor(idxTableName);
        desc.addFamily(new HColumnDescriptor(
            SecondaryIndexConstants.INDEX_TABLE_IDX_CF_NAME));
        admin.createTable(desc);
      }
    } finally {
      if (admin != null) {
        admin.close();
      }
    }
  }

  public void deleteIndexTable(byte[] idxTableName) throws IOException {
    HBaseAdmin admin = null;
    try {
      admin = new HBaseAdmin(getConfiguration());
      if (admin.tableExists(idxTableName)) {
        admin.disableTable(idxTableName);
        admin.deleteTable(idxTableName);
        LOG.info("Index for " + Bytes.toString(idxTableName) +
            " of table " + Bytes.toString(getTableName()) + " has been deleted.");
      }
    } catch (IOException ioe) {
      throw new IndexNotFoundException(
          "Index for " + Bytes.toString(idxTableName) +
              " of table " + Bytes.toString(getTableName()) + " not found.");
    } finally {
      if (admin != null) {
        admin.close();
      }
    }
  }

  private static final byte[] EMPTY = new byte[0];

  public Result[] getBySecondaryIndex(byte[] family, byte[] qualifier,
      byte[] value)
  throws IOException, ClassNotFoundException {
    return getBySecondaryIndex(family, qualifier, value,
        new ArrayList<Column>());
  }

  public Result[] getBySecondaryIndex(byte[] family, byte[] qualifier,
      byte[] value, List<Column> projectColumns)
  throws IOException, ClassNotFoundException {
    long startTime = System.nanoTime();
    Result[] result = null;
    HTableInterface idxTable = this.getConnection().getTable(
        Util.getSecondaryIndexTableName(getTableName(), family, qualifier));
    try {
      Result temp = idxTable.get(new Get(value));
      byte[] serializedTreeSet = temp.getValue(
          Bytes.toBytes(SecondaryIndexConstants.INDEX_TABLE_IDX_CF_NAME),
          Bytes.toBytes(SecondaryIndexConstants.INDEX_TABLE_IDX_C_NAME));
      TreeSet<byte[]> primaryRowKeys;

      if (serializedTreeSet != null) {
        LOG.trace("SerializedTreeSetNotNull");
        primaryRowKeys = Util.deserializeIndex(serializedTreeSet);

        if (!primaryRowKeys.isEmpty()) {
          // Check for empty projection
          if (projectColumns.isEmpty()) {
            result = new Result[primaryRowKeys.size()];
            int i = 0;
            for (byte[] rowKey : primaryRowKeys) {
              Cell c = new KeyValue(rowKey, EMPTY, EMPTY, EMPTY);
              result[i++] = Result.create(Arrays.asList(c));
            }
          }

          else {
            List<Get> getList = new ArrayList<Get>();
            for (byte[] rowKey : primaryRowKeys) {
              Get get = new Get(rowKey);
              for (Column column : projectColumns) {
                get.addColumn(column.getFamily(), column.getQualifier());
              }
              getList.add(get);
            }
            result = get(getList);
          }
        }
      } else {
        LOG.trace("SerializedTreeSetNull");
      }
      long duration = (System.nanoTime() - startTime) / 1000;
      LOG.trace("getBySecondaryIndex: " + duration + " us");
      return result;
    } finally {
      if (idxTable != null) {
        idxTable.close();
      }
    }
  }
}
